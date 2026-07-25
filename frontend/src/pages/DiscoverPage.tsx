import { useEffect, useRef, useState, useCallback } from 'react'
import { Link, useNavigate, useNavigationType, useSearchParams } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import GenreFilter from '../components/GenreFilter'
import JustWatchAttribution from '../components/JustWatchAttribution'
import { PersonSilhouette } from '../components/OverviewCastPanel'
import TitleCard, { cardKey } from '../components/TitleCard'
import type { AddHandler, CardStatus, DismissHandler, OpenHandler, RemoveHandler, ToggleHandler } from '../components/TitleCard'
import { useTitleCardActions } from '../hooks/useTitleCardActions'
import { catalogFor } from '../utils/genreCatalog'
import type { Genre, GenreCatalog, PersonSearchResult, ShelfKind, SuggestionShelf, TitleSearchResponse, TitleType, WatchlistEntryResponse, WatchProvider } from '../types/api'

// Scroll offset saved when opening a title so back-navigation can restore it (#241)
const SCROLL_STORAGE_KEY = 'wewatch:discover-scroll'

// Mirrors DiscoverPolicy.MAX_FETCH_PAGE — the browse endpoint rejects anything
// deeper, so "Load more" stops here rather than asking for a 400 (#384)
const MAX_BROWSE_PAGE = 6

// Franchise continuation first — the highest-precision suggestion class (#272)
// outranks everything else. Similarity shelves next, the pooled catch-all
// after them (#266), exploration shelves last (#235); ties keep backend order
const SHELF_KIND_ORDER: Record<ShelfKind, number> = {
  // Built after FRANCHISE (which has the more precise claim on the dedup set) but
  // shown before it: "what can we both watch" is the question a household opens
  // the app with, so it leads (#322)
  BOTH_WATCH: -2,
  FRANCHISE: -1,
  GENRE_PROFILE: 0,
  PER_SEED: 1,
  FINISHED_SEED: 2,
  MORE_PICKS: 3,
  // Hidden gems (#376) is always-on and taste-scored, so it outranks the
  // exploration kinds below it — which rotate by lottery — and sits behind the
  // similarity shelves that have the more precise claim
  HIDDEN_GEMS: 4,
  NEW_RELEASES: 5,
  TRENDING: 5,
  PERSON: 5,
  KEYWORD: 5,
}

interface ShelfRowProps {
  titles: TitleSearchResponse[]
  cardStatus: Record<string, CardStatus>
  pickingKey: string | null
  onAdd: AddHandler
  onChangeStatus: AddHandler
  onTogglePicker: ToggleHandler
  onOpen: OpenHandler
  onRemove: RemoveHandler
  onDismiss: DismissHandler
  providersById?: Map<number, WatchProvider>
}

function ShelfRow({ titles, cardStatus, pickingKey, onAdd, onChangeStatus, onTogglePicker, onOpen, onRemove, onDismiss, providersById }: ShelfRowProps) {
  const rowRef = useRef<HTMLDivElement>(null)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)

  const updateArrows = useCallback(() => {
    const el = rowRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 0)
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1)
  }, [])

  useEffect(() => {
    const el = rowRef.current
    if (!el) return
    updateArrows()
    el.addEventListener('scroll', updateArrows, { passive: true })
    const ro = new ResizeObserver(updateArrows)
    ro.observe(el)
    return () => {
      el.removeEventListener('scroll', updateArrows)
      ro.disconnect()
    }
  }, [titles, updateArrows])

  function scrollBy(dir: 'left' | 'right') {
    const el = rowRef.current
    if (!el) return
    el.scrollBy({ left: dir === 'left' ? -(el.clientWidth * 0.8) : el.clientWidth * 0.8, behavior: 'smooth' })
  }

  return (
    <div className="shelf-scroll-container">
      <button
        className="shelf-arrow shelf-arrow-left"
        onClick={() => scrollBy('left')}
        disabled={!canScrollLeft}
        aria-label="Scroll left"
      >&#8249;</button>
      <div ref={rowRef} className="suggestion-shelf-row">
        {titles.map(title => (
          <TitleCard
            key={cardKey(title)}
            title={title}
            status={cardStatus[cardKey(title)] ?? 'idle'}
            isPicking={pickingKey === cardKey(title)}
            onAdd={onAdd}
            onChangeStatus={onChangeStatus}
            onTogglePicker={onTogglePicker}
            onOpen={onOpen}
            onRemove={onRemove}
            onDismiss={onDismiss}
            providersById={providersById}
          />
        ))}
      </div>
      <button
        className="shelf-arrow shelf-arrow-right"
        onClick={() => scrollBy('right')}
        disabled={!canScrollRight}
        aria-label="Scroll right"
      >&#8250;</button>
    </div>
  )
}

function DiscoverPage() {
  const api = useApi()
  const { watchlists, selectedWatchlistId, selectWatchlist } = useWatchlists()
  const navigate = useNavigate()
  const navigationType = useNavigationType()
  // Search query lives in the URL (?q=) so back-navigation and refresh restore it
  // (#241). Genre browsing (#384) puts ?genres= and ?medium= there for the same reason.
  const [searchParams, setSearchParams] = useSearchParams()
  const query = searchParams.get('q') ?? ''

  // Write params while preserving the others. This used to be
  // setSearchParams(q ? { q } : {}), which replaces the *whole* query string — with
  // ?q= the only param that was invisible, but it drops ?genres= and ?medium= on
  // every keystroke. Same bug #382 fixed on LibraryPage; same fix.
  const updateSearchParams = useCallback((updates: Record<string, string | null>) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      for (const [key, value] of Object.entries(updates)) {
        if (value) next.set(key, value)
        else next.delete(key)
      }
      return next
    }, { replace: true })
  }, [setSearchParams])
  const setQuery = (q: string) => updateSearchParams({ q: q || null })
  const searchInputRef = useRef<HTMLInputElement>(null)
  const [results, setResults] = useState<TitleSearchResponse[]>([])
  // Person hits for the slim "People" row above the title grid (#356)
  const [people, setPeople] = useState<PersonSearchResult[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const {
    cardStatus,
    setCardStatus,
    setEntryIds,
    pickingKey,
    handleAddToWatchlist,
    togglePicker,
    handleChangeStatus,
    handleRemove,
  } = useTitleCardActions(api, selectedWatchlistId)
  const [suggestions, setSuggestions] = useState<SuggestionShelf[]>([])
  const [suggestionsLoading, setSuggestionsLoading] = useState(false)
  // ── Genre browsing (#384) ──
  const [genreCatalog, setGenreCatalog] = useState<GenreCatalog | null>(null)
  // Whether the catalog call has settled either way. Which mode the page is in
  // depends on the catalog (a selected id is only real if that medium has it), so
  // without this a deep-linked ?genres= flashes shelves for one round trip and
  // fetches them for nothing.
  const [catalogSettled, setCatalogSettled] = useState(false)
  // The selected watchlist's entries, kept only to pick the toggle's default
  // medium; every mode that needs them for reconciling fetches its own copy.
  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [browseResults, setBrowseResults] = useState<TitleSearchResponse[]>([])
  const [browsePage, setBrowsePage] = useState(1)
  const [browseLoading, setBrowseLoading] = useState(false)
  const [browseAppending, setBrowseAppending] = useState(false)
  const [browseError, setBrowseError] = useState<string | null>(null)
  // #305 reconcile-before-paint: tiles stay unmounted until the cardStatus/entryIds
  // seed has landed once, or an optimistic add can be reverted to "+" by a
  // reconcile that lands after it
  const [browseSeeded, setBrowseSeeded] = useState(false)
  // A page that came back empty is the end of the feed — TMDB has fewer than six
  // pages for a narrow AND far more often than for a single genre
  const [browseExhausted, setBrowseExhausted] = useState(false)
  // Optimistically hidden "Not interested" tiles (#268) — the backend excludes
  // them from the next compute; this filters the already-fetched shelves
  const [dismissedKeys, setDismissedKeys] = useState<Set<string>>(new Set())
  const [undoTarget, setUndoTarget] = useState<TitleSearchResponse | null>(null)
  const undoTimerRef = useRef<number | null>(null)
  // id -> provider lookup for availability badges (#270); stays null when the
  // user has no streaming services configured, which hides all badge UI
  const [providersById, setProvidersById] = useState<Map<number, WatchProvider> | null>(null)

  useEffect(() => {
    let cancelled = false
    api.getMe()
      .then(user => {
        if (cancelled || !user.watchRegion || !user.watchProviderIds?.length) return
        return api.getWatchProviders(user.watchRegion).then(list => {
          if (!cancelled) setProvidersById(new Map(list.map(p => [p.id, p])))
        })
      })
      .catch(() => { /* badges are decoration — fail silently */ })
    return () => { cancelled = true }
  }, [api])

  useEffect(() => () => {
    if (undoTimerRef.current) clearTimeout(undoTimerRef.current)
  }, [])

  // The genre catalog, once (#384). The backend degrades to empty lists on a TMDB
  // outage rather than failing, so this rejects only on a real transport error —
  // and either way an absent catalog just hides the filter, leaving the page as it
  // was before this feature.
  useEffect(() => {
    let cancelled = false

    Promise.resolve()
      .then(() => api.getGenres())
      .then(data => { if (!cancelled) setGenreCatalog(data) })
      .catch(() => { if (!cancelled) setGenreCatalog(null) })
      .finally(() => { if (!cancelled) setCatalogSettled(true) })

    return () => { cancelled = true }
  }, [api])

  // ── Which mode the page is in (#384) ──
  //
  // Selecting genres is what puts Discover into a medium-scoped mode. With none
  // selected nothing here changes: the text search stays the all-medium multi
  // search and an empty box still renders the suggestion shelves.
  const hasQuery = query.trim().length > 0
  // The list's own lean, so a household that mostly watches TV doesn't have to
  // toggle every time. Falls back to Movies before the entries land.
  const dominantMedium: TitleType | null = entries.length === 0
    ? null
    : entries.filter(e => e.type === 'TV').length > entries.filter(e => e.type === 'MOVIE').length
      ? 'TV'
      : 'MOVIE'
  const paramMedium = searchParams.get('medium')
  const medium: TitleType = paramMedium === 'TV' || paramMedium === 'MOVIE'
    ? paramMedium
    : dominantMedium ?? 'MOVIE'
  // The whole catalog for this medium, not the genres present on some list: this
  // browses TMDB, so every genre it has is on offer.
  const genreOptions = catalogFor(genreCatalog, medium)
  // Both catalogs, so the picker's own Movies/TV switch can swap the offered
  // checkboxes with no URL write (#398). The medium commits with Apply, never on
  // the toggle — see GenreFilter.
  const mediumOptions: Record<TitleType, Genre[]> = {
    MOVIE: catalogFor(genreCatalog, 'MOVIE'),
    TV: catalogFor(genreCatalog, 'TV'),
  }
  const urlGenreIds = (searchParams.get('genres') ?? '')
    .split(',')
    .filter(part => part !== '')
    .map(Number)
    .filter(id => Number.isInteger(id) && id > 0)
  // Intersected, not pruned out of the URL — #382's reasoning: a prune effect would
  // need gating on "the catalog has loaded" or it wipes a cold deep link before the
  // fetch lands. Here it also means toggling Movies → TV → Movies gives the movie
  // genres back rather than having quietly dropped them.
  const optionIds = new Set(genreOptions.map(g => g.id))
  const activeGenreIds = urlGenreIds.filter(id => optionIds.has(id))
  // A string, so the browse effect can depend on the selection without a fresh
  // array identity re-firing it every render
  const genresParam = activeGenreIds.join(',')
  const genreNamesById = new Map(genreOptions.map(g => [g.id, g.name]))
  const activeGenreLabel = activeGenreIds
    .map(id => genreNamesById.get(id))
    .filter((name): name is string => !!name)
    .join(' + ')
  const mediumNoun = medium === 'TV' ? 'TV' : 'movie'

  // Query + genres: a committed selection scopes the search server-side to its
  // medium (#398) — `/3/search/multi` returns both mediums by design (#356), and
  // that's what made the old client-only AND inconsistent per genre: Comedy (35,
  // shared) left both mediums in the grid while Science Fiction (878, movie-only)
  // silently dropped every TV result. Genres still AND client-side on top.
  const visibleResults = activeGenreIds.length > 0
    ? results.filter(t => activeGenreIds.every(id => (t.genreIds ?? []).includes(id)))
    : results
  const searchPlaceholder = activeGenreIds.length > 0
    ? `Search ${medium === 'TV' ? 'TV shows' : 'movies'}…`
    : 'Search movies and TV shows…'
  const clearGenres = () => updateSearchParams({ genres: null })

  const browseMode = catalogSettled && !hasQuery && activeGenreIds.length > 0
  // Every selected id belongs to the *other* medium — TMDB's TV catalog has no
  // Romance, Horror or Thriller at all, so toggling can empty a real selection.
  // Saying so beats silently falling back to shelves as if nothing was asked.
  const crossMediumOnly =
    catalogSettled && !hasQuery && urlGenreIds.length > 0 && activeGenreIds.length === 0
  // Genres are in the URL but the catalog hasn't settled, so we don't know yet
  // whether this is browse mode. Hold the shelves rather than fetch them to throw away.
  const genresPending = !catalogSettled && urlGenreIds.length > 0

  // Seeds cardStatus/entryIds for a set of freshly-fetched titles from the
  // watchlist's entries (#305). Shared by the first browse page and each appended one.
  const seedCardState = useCallback((
    titles: TitleSearchResponse[],
    watchlistEntries: WatchlistEntryResponse[],
  ) => {
    const entryByKey = new Map(
      watchlistEntries.map(e => [`${e.externalSource}-${e.externalId}`, e]),
    )
    setCardStatus(prev => {
      const next = { ...prev }
      titles.forEach(title => {
        const existing = entryByKey.get(cardKey(title))
        if (existing) next[cardKey(title)] = existing.status
      })
      return next
    })
    setEntryIds(prev => {
      const next = { ...prev }
      titles.forEach(title => {
        const existing = entryByKey.get(cardKey(title))
        if (existing) next[cardKey(title)] = existing.id
      })
      return next
    })
  }, [setCardStatus, setEntryIds])

  // Search effect
  useEffect(() => {
    if (!query.trim()) {
      setResults([])
      setPeople([])
      setSearched(false)
      return
    }
    // Genres are in the URL but the catalog hasn't settled, so whether this search
    // is medium-scoped isn't known yet. Firing unscoped now would just re-fire
    // scoped the moment the catalog lands (#398) — same reasoning as browseMode.
    if (genresPending) return

    // Derived from genresParam rather than reading the outer activeGenreIds, same
    // idiom as the browse effect below — activeGenreIds isn't in the dependency
    // array (a fresh array every render would re-fire this forever).
    const scoped = genresParam.length > 0

    const timer = setTimeout(async () => {
      if (!selectedWatchlistId) return
      setIsLoading(true)
      setError(null)
      try {
        const [data, watchlist] = await Promise.all([
          scoped ? api.searchTitles(query, medium) : api.searchTitles(query),
          api.getWatchlistEntries(selectedWatchlistId),
        ])
        const entryByKey = new Map(
          watchlist.map(e => [`${e.externalSource}-${e.externalId}`, e])
        )
        setEntries(watchlist)
        setResults(data.titles)
        // People aren't watchlist entries — they get their own row and skip
        // the cardStatus/entryIds reconcile entirely (#356).
        setPeople(data.people)
        setSearched(true)
        setCardStatus(prev => {
          const next = { ...prev }
          data.titles.forEach(title => {
            const k = cardKey(title)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.status
            else if (next[k] !== 'loading') delete next[k]
          })
          return next
        })
        setEntryIds(prev => {
          const next = { ...prev }
          data.titles.forEach(title => {
            const k = cardKey(title)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.id
            else delete next[k]
          })
          return next
        })
      } catch {
        setError('Search failed. Please try again.')
      } finally {
        setIsLoading(false)
      }
    }, 300)

    return () => clearTimeout(timer)
    // genresParam (a string) rather than activeGenreIds (a fresh array every render)
    // — same reasoning as the browse effect below.
  }, [query, api, selectedWatchlistId, setCardStatus, setEntryIds, genresPending, genresParam, medium])

  // Suggestions effect (when the query is empty and no genres are selected)
  useEffect(() => {
    // Browse answers the same empty-query slot when genres are selected, and the
    // two must not both fetch: shelves the user will never see still cost a
    // compute and record impressions that sink those titles tomorrow (#264).
    if (query.trim() || !selectedWatchlistId || browseMode || crossMediumOnly || genresPending) {
      setSuggestions([])
      return
    }

    let cancelled = false
    setSuggestionsLoading(true)

    Promise.all([
      api.getSuggestions(selectedWatchlistId),
      api.getWatchlistEntries(selectedWatchlistId),
    ])
      .then(([shelves, entries]) => {
        if (cancelled) return
        setSuggestions(shelves)
        setEntries(entries)
        const entryByKey = new Map(
          entries.map(e => [`${e.externalSource}-${e.externalId}`, e])
        )
        setCardStatus(prev => {
          const next = { ...prev }
          shelves.flatMap(s => s.titles).forEach(title => {
            const k = cardKey(title)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.status
          })
          return next
        })
        setEntryIds(prev => {
          const next = { ...prev }
          shelves.flatMap(s => s.titles).forEach(title => {
            const k = cardKey(title)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.id
          })
          return next
        })
      })
      .catch(() => {
        // Fail silently — suggestions are non-critical
      })
      .finally(() => {
        if (!cancelled) setSuggestionsLoading(false)
      })

    return () => { cancelled = true }
  }, [query, api, selectedWatchlistId, setCardStatus, setEntryIds,
    browseMode, crossMediumOnly, genresPending])

  // Browse effect (#384): the first page for the current medium + genre selection.
  // Keyed on the selection as a string rather than the id array, whose identity
  // changes every render and would re-fire this forever.
  useEffect(() => {
    if (!browseMode || !selectedWatchlistId) {
      setBrowseResults([])
      setBrowseSeeded(false)
      return
    }

    let cancelled = false
    setBrowseLoading(true)
    setBrowseError(null)
    setBrowsePage(1)
    setBrowseExhausted(false)
    // Cleared, not left in place: the tiles on screen belong to the previous
    // selection, and "Loading Comedy…" over a grid of Romance is a lie
    setBrowseResults([])
    setBrowseSeeded(false)
    const genreIds = genresParam.split(',').map(Number)

    Promise.all([
      api.browseByGenre(selectedWatchlistId, medium, genreIds, 1),
      api.getWatchlistEntries(selectedWatchlistId),
    ])
      .then(([titles, watchlistEntries]) => {
        if (cancelled) return
        setEntries(watchlistEntries)
        // Seeded from the same promise that produced the titles, so the tiles
        // cannot paint before the reconcile — the #363 shape, not #358's race
        seedCardState(titles, watchlistEntries)
        setBrowseResults(titles)
        setBrowseExhausted(titles.length === 0)
        setBrowseSeeded(true)
      })
      .catch(() => {
        if (!cancelled) setBrowseError('Couldn’t load those genres. Please try again.')
      })
      .finally(() => {
        if (!cancelled) setBrowseLoading(false)
      })

    return () => { cancelled = true }
  }, [browseMode, medium, genresParam, api, selectedWatchlistId, seedCardState])

  // Restore scroll on back-navigation once the content giving the page its
  // height has rendered; anything else invalidates the saved offset (#241).
  const scrollRestoredRef = useRef(false)
  useEffect(() => {
    if (scrollRestoredRef.current) return
    if (navigationType !== 'POP') {
      sessionStorage.removeItem(SCROLL_STORAGE_KEY)
      scrollRestoredRef.current = true
      return
    }
    const contentReady = query.trim()
      ? !isLoading && searched
      : browseMode
        ? !browseLoading && browseResults.length > 0
        : !suggestionsLoading && suggestions.length > 0
    if (!contentReady) return
    scrollRestoredRef.current = true
    const saved = Number(sessionStorage.getItem(SCROLL_STORAGE_KEY))
    if (saved > 0) window.scrollTo(0, saved)
  }, [navigationType, query, isLoading, searched, suggestionsLoading, suggestions.length,
    browseMode, browseLoading, browseResults.length])

  // "Load more" appends the next page rather than replacing the grid, and stops at
  // the endpoint's depth cap. Imperative rather than an effect keyed on the page,
  // so nothing re-fetches page 1 on the way.
  async function handleLoadMore() {
    if (!selectedWatchlistId || browseAppending || browsePage >= MAX_BROWSE_PAGE) return
    const nextPage = browsePage + 1
    setBrowseAppending(true)
    setBrowseError(null)
    try {
      const [titles, watchlistEntries] = await Promise.all([
        api.browseByGenre(selectedWatchlistId, medium, activeGenreIds, nextPage),
        api.getWatchlistEntries(selectedWatchlistId),
      ])
      seedCardState(titles, watchlistEntries)
      // Deduped on append: pages shouldn't overlap, but a repeated key would break
      // React's reconciliation, which is a worse failure than a missing tile
      setBrowseResults(prev => {
        const shown = new Set(prev.map(cardKey))
        return [...prev, ...titles.filter(t => !shown.has(cardKey(t)))]
      })
      setBrowsePage(nextPage)
      if (titles.length === 0) setBrowseExhausted(true)
    } catch {
      setBrowseError('Couldn’t load more titles. Please try again.')
    } finally {
      setBrowseAppending(false)
    }
  }

  function openTitle(title: TitleSearchResponse) {
    sessionStorage.setItem(SCROLL_STORAGE_KEY, String(window.scrollY))
    navigate(
      `/title/${title.type.toLowerCase()}/${title.externalSource}/${title.externalId}`,
      { state: { title } },
    )
  }

  async function handleDismiss(title: TitleSearchResponse) {
    const key = cardKey(title)
    setDismissedKeys(prev => new Set(prev).add(key))
    if (undoTimerRef.current) clearTimeout(undoTimerRef.current)
    setUndoTarget(title)
    undoTimerRef.current = window.setTimeout(() => setUndoTarget(null), 6000)
    try {
      await api.dismissSuggestion(title.externalId)
    } catch {
      // Revert the optimistic removal — the dismissal never landed
      setDismissedKeys(prev => {
        const next = new Set(prev)
        next.delete(key)
        return next
      })
      setUndoTarget(prev => (prev && cardKey(prev) === key ? null : prev))
    }
  }

  async function handleUndoDismiss() {
    const title = undoTarget
    if (!title) return
    const key = cardKey(title)
    if (undoTimerRef.current) clearTimeout(undoTimerRef.current)
    setUndoTarget(null)
    setDismissedKeys(prev => {
      const next = new Set(prev)
      next.delete(key)
      return next
    })
    try {
      await api.undoDismissSuggestion(title.externalId)
    } catch {
      // The dismissal still stands server-side, so hide the tile again
      setDismissedKeys(prev => new Set(prev).add(key))
    }
  }

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Discover</p>
          <h2>Find something to watch.</h2>
          <div className="search-input-wrapper">
            <input
              ref={searchInputRef}
              className="search-input"
              type="search"
              placeholder={searchPlaceholder}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              autoFocus
            />
            {query && (
              <button
                className="search-clear-btn"
                onClick={() => { setQuery(''); searchInputRef.current?.focus() }}
                aria-label="Clear search"
              >
                ✕
              </button>
            )}
          </div>

          {/* Genre picker, medium switch folded in (#398). Always shown once the
              catalog loads — the trigger is the single place the medium is stated,
              reading "Genres" / "Movies • Comedy" / "TV • 3 Genres". */}
          {(mediumOptions.MOVIE.length > 0 || mediumOptions.TV.length > 0) && (
            <div className="library-filter-row discover-filter-row">
              <GenreFilter
                options={genreOptions}
                selected={activeGenreIds}
                medium={medium}
                mediumOptions={mediumOptions}
                // Writes the medium alongside the genres, so a browse URL always
                // carries the one it was built for and can't be re-derived out from
                // under a shared link
                onApply={(ids, nextMedium) => updateSearchParams({
                  genres: ids.length > 0 ? ids.join(',') : null,
                  medium: nextMedium ?? medium,
                })}
              />
            </div>
          )}

          {watchlists.length > 1 && (
            <div className="discover-watchlist-picker">
              <span className="discover-picker-label">Adding to:</span>
              <select
                className="discover-picker-select"
                value={selectedWatchlistId ?? ''}
                onChange={e => selectWatchlist(Number(e.target.value))}
              >
                {watchlists.map(wl => (
                  <option key={wl.id} value={wl.id}>{wl.name}</option>
                ))}
              </select>
            </div>
          )}
        </div>
      </section>

      <section className="stack-list">
        {/* Search results */}
        {query.trim() && (
          <>
            {/* Scope line (#398): the search is medium-scoped server-side whenever a
                genre selection is committed. "Search everything" is clearGenres under
                a name that reads right for search, not just for the genre picker —
                clearing also brings the People row back. */}
            {activeGenreIds.length > 0 && (
              <p className="discover-scope-line">
                {medium === 'TV' ? 'TV' : 'Movies'} • {activeGenreLabel}{' '}
                <button className="link-button" onClick={clearGenres}>Search everything</button>
              </p>
            )}
            {isLoading && <p className="search-status">Searching…</p>}
            {error && <p className="search-status search-status-error">{error}</p>}
            {!isLoading && searched && results.length === 0 && people.length === 0
              && activeGenreIds.length === 0 && (
              <p className="search-status">No results for &ldquo;{query}&rdquo;.</p>
            )}
            {/* A ≤20-result search AND-ed against two genres is often empty. That has
                to read as "no matches", not as a broken page. The escape hatch lives
                in the scope line above — not duplicated here as a second identically
                labelled button. */}
            {!isLoading && searched && visibleResults.length === 0 && activeGenreIds.length > 0 && (
              <p className="search-status">
                No {mediumNoun} results for &ldquo;{query}&rdquo; in {activeGenreLabel}.
              </p>
            )}
            {people.length > 0 && (
              <section className="people-row" aria-label="People">
                <h2 className="people-row-heading">People</h2>
                <div className="people-row-tiles">
                  {/* Server ranks by popularity; CSS caps this at 2 on mobile,
                      4 on web (#356). */}
                  {people.slice(0, 4).map(person => (
                    <Link
                      key={person.id}
                      to={`/person/${person.id}`}
                      className="people-row-item"
                    >
                      {person.profileUrl
                        ? <img className="people-row-photo" src={person.profileUrl} alt="" loading="lazy" />
                        : <div className="people-row-photo"><PersonSilhouette /></div>}
                      <span className="people-row-name">{person.name}</span>
                    </Link>
                  ))}
                </div>
              </section>
            )}
            {visibleResults.length > 0 && (
              <div className="title-grid">
                {visibleResults.map(title => (
                  <TitleCard
                    key={cardKey(title)}
                    title={title}
                    status={cardStatus[cardKey(title)] ?? 'idle'}
                    isPicking={pickingKey === cardKey(title)}
                    onAdd={handleAddToWatchlist}
                    onChangeStatus={handleChangeStatus}
                    onTogglePicker={togglePicker}
                    onOpen={openTitle}
                    onRemove={handleRemove}
                  />
                ))}
              </div>
            )}
          </>
        )}

        {/* Every selected genre belongs to the other medium (#384). The medium switch
            lives inside the Genres picker now (#398), so this points there instead
            of naming a control that used to sit beside it. */}
        {crossMediumOnly && (
          <p className="search-status">
            {medium === 'TV'
              ? 'Those genres aren’t in TMDB’s TV catalog — TV has no Romance, Horror or Thriller. Pick from TV’s genres in the picker, or clear them.'
              : 'Those genres aren’t in TMDB’s movie catalog. Pick from the movie genres in the picker, or clear them.'}{' '}
            <button className="link-button" onClick={clearGenres}>Clear genres</button>
          </p>
        )}

        {/* Genre browse (#384): the taste-ranked feed for the selected genres */}
        {browseMode && (
          <>
            {browseLoading && <p className="search-status">Finding {activeGenreLabel}…</p>}
            {browseError && <p className="search-status search-status-error">{browseError}</p>}
            {!browseLoading && browseSeeded && browseResults.length === 0 && (
              <p className="search-status">
                Nothing in {activeGenreLabel} left to suggest — everything TMDB has is
                already on your list or dismissed. Try fewer genres.{' '}
                <button className="link-button" onClick={clearGenres}>Clear genres</button>
              </p>
            )}
            {/* Gated on the reconcile having landed once (#305) */}
            {browseSeeded && browseResults.length > 0 && (
              <>
                <p className="suggestion-shelf-heading">
                  {activeGenreLabel} · {medium === 'TV' ? 'TV' : 'Movies'}
                </p>
                <div className="title-grid">
                  {browseResults.map(title => (
                    <TitleCard
                      key={cardKey(title)}
                      title={title}
                      status={cardStatus[cardKey(title)] ?? 'idle'}
                      isPicking={pickingKey === cardKey(title)}
                      onAdd={handleAddToWatchlist}
                      onChangeStatus={handleChangeStatus}
                      onTogglePicker={togglePicker}
                      onOpen={openTitle}
                      onRemove={handleRemove}
                      providersById={providersById ?? undefined}
                    />
                  ))}
                </div>
                {browsePage < MAX_BROWSE_PAGE && !browseExhausted && (
                  <button
                    className="browse-load-more"
                    onClick={handleLoadMore}
                    disabled={browseAppending}
                  >
                    {browseAppending ? 'Loading…' : 'Load more'}
                  </button>
                )}
                {providersById && <JustWatchAttribution />}
              </>
            )}
          </>
        )}

        {/* Suggestion shelves (when the query is empty and no genres are selected) */}
        {!query.trim() && !browseMode && !crossMediumOnly && !genresPending && (
          <>
            {suggestionsLoading && <p className="search-status">Loading suggestions…</p>}
            {!suggestionsLoading && (() => {
              const sorted = [...suggestions].sort(
                (a, b) => (SHELF_KIND_ORDER[a.kind] ?? 6) - (SHELF_KIND_ORDER[b.kind] ?? 6)
              )
              const shownKeys = new Set<string>()
              return sorted.map(shelf => {
                const dedupedTitles = shelf.titles.filter(t => {
                  const k = cardKey(t)
                  if (dismissedKeys.has(k) || shownKeys.has(k)) return false
                  shownKeys.add(k)
                  return true
                })
                if (dedupedTitles.length === 0) return null
                return (
                  <div key={shelf.reason} className="suggestion-shelf">
                    <p className="suggestion-shelf-heading">
                      {shelf.reason}
                      {shelf.providerFiltered && (
                        <span className="shelf-provider-chip">
                          {shelf.kind === 'BOTH_WATCH'
                            ? 'On services you share'
                            : 'On your services'}
                        </span>
                      )}
                    </p>
                    <ShelfRow
                      titles={dedupedTitles}
                      cardStatus={cardStatus}
                      pickingKey={pickingKey}
                      onAdd={handleAddToWatchlist}
                      onChangeStatus={handleChangeStatus}
                      onTogglePicker={togglePicker}
                      onOpen={openTitle}
                      onRemove={handleRemove}
                      onDismiss={handleDismiss}
                      providersById={providersById ?? undefined}
                    />
                  </div>
                )
              })
            })()}
            {providersById && suggestions.length > 0 && !suggestionsLoading && (
              <JustWatchAttribution />
            )}
          </>
        )}
      </section>

      {undoTarget && (
        <div className="undo-snackbar" role="status">
          <span className="undo-snackbar-text">
            We won&rsquo;t suggest &ldquo;{undoTarget.name}&rdquo; again
          </span>
          <button className="undo-snackbar-btn" onClick={handleUndoDismiss}>
            Undo
          </button>
        </div>
      )}
    </div>
  )
}

export default DiscoverPage
