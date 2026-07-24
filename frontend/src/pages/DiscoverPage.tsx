import { useEffect, useRef, useState, useCallback } from 'react'
import { Link, useNavigate, useNavigationType, useSearchParams } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import JustWatchAttribution from '../components/JustWatchAttribution'
import { PersonSilhouette } from '../components/OverviewCastPanel'
import TitleCard, { cardKey } from '../components/TitleCard'
import type { AddHandler, CardStatus, DismissHandler, OpenHandler, RemoveHandler, ToggleHandler } from '../components/TitleCard'
import { useTitleCardActions } from '../hooks/useTitleCardActions'
import type { PersonSearchResult, ShelfKind, SuggestionShelf, TitleSearchResponse, WatchProvider } from '../types/api'

// Scroll offset saved when opening a title so back-navigation can restore it (#241)
const SCROLL_STORAGE_KEY = 'wewatch:discover-scroll'

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
  // Search query lives in the URL (?q=) so back-navigation and refresh restore it (#241)
  const [searchParams, setSearchParams] = useSearchParams()
  const query = searchParams.get('q') ?? ''
  const setQuery = (q: string) => setSearchParams(q ? { q } : {}, { replace: true })
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

  // Search effect
  useEffect(() => {
    if (!query.trim()) {
      setResults([])
      setPeople([])
      setSearched(false)
      return
    }

    const timer = setTimeout(async () => {
      if (!selectedWatchlistId) return
      setIsLoading(true)
      setError(null)
      try {
        const [data, watchlist] = await Promise.all([
          api.searchTitles(query),
          api.getWatchlistEntries(selectedWatchlistId),
        ])
        const entryByKey = new Map(
          watchlist.map(e => [`${e.externalSource}-${e.externalId}`, e])
        )
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
  }, [query, api, selectedWatchlistId, setCardStatus, setEntryIds])

  // Suggestions effect (when query is empty)
  useEffect(() => {
    if (query.trim() || !selectedWatchlistId) {
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
  }, [query, api, selectedWatchlistId, setCardStatus, setEntryIds])

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
      : !suggestionsLoading && suggestions.length > 0
    if (!contentReady) return
    scrollRestoredRef.current = true
    const saved = Number(sessionStorage.getItem(SCROLL_STORAGE_KEY))
    if (saved > 0) window.scrollTo(0, saved)
  }, [navigationType, query, isLoading, searched, suggestionsLoading, suggestions.length])

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
              placeholder="Search movies and TV shows…"
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
            {isLoading && <p className="search-status">Searching…</p>}
            {error && <p className="search-status search-status-error">{error}</p>}
            {!isLoading && searched && results.length === 0 && people.length === 0 && (
              <p className="search-status">No results for &ldquo;{query}&rdquo;.</p>
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
            {results.length > 0 && (
              <div className="title-grid">
                {results.map(title => (
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

        {/* Suggestion shelves (when query is empty) */}
        {!query.trim() && (
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
