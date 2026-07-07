import { useEffect, useRef, useState, useCallback } from 'react'
import { useNavigate, useNavigationType, useSearchParams } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import StatusPicker, { STATUS_LABELS } from '../components/StatusPicker'
import JustWatchAttribution from '../components/JustWatchAttribution'
import type { ShelfKind, SuggestionShelf, TitleSearchResponse, WatchProvider, WatchStatus } from '../types/api'

type AddHandler = (title: TitleSearchResponse, status: WatchStatus) => void
type OpenHandler = (title: TitleSearchResponse) => void
type ToggleHandler = (title: TitleSearchResponse) => void
type RemoveHandler = (title: TitleSearchResponse) => void
type DismissHandler = (title: TitleSearchResponse) => void

type CardStatus = 'idle' | 'loading' | 'error' | WatchStatus

function cardKey(title: TitleSearchResponse) {
  return `${title.externalSource}-${title.externalId}`
}

// Scroll offset saved when opening a title so back-navigation can restore it (#241)
const SCROLL_STORAGE_KEY = 'wewatch:discover-scroll'

// Similarity shelves first, the pooled catch-all after them (#266), exploration
// shelves last (#235); ties keep backend order
const SHELF_KIND_ORDER: Record<ShelfKind, number> = {
  GENRE_PROFILE: 0,
  PER_SEED: 1,
  FINISHED_SEED: 2,
  MORE_PICKS: 3,
  NEW_RELEASES: 4,
  HIDDEN_GEMS: 4,
  TRENDING: 4,
  PERSON: 4,
  KEYWORD: 4,
}

interface TitleCardProps {
  title: TitleSearchResponse
  status: CardStatus
  isPicking: boolean
  onAdd: AddHandler
  onChangeStatus: AddHandler
  onTogglePicker: ToggleHandler
  onOpen: OpenHandler
  onRemove: RemoveHandler
  // Only suggestion tiles get the "Not interested" affordance (#268); search
  // results have no dismiss concept, so the prop is absent there
  onDismiss?: DismissHandler
  // id -> provider lookup for availability badges (#270); absent on search
  // results and when the user has no streaming services configured
  providersById?: Map<number, WatchProvider>
}

function TitleCard({ title, status, isPicking, onAdd, onChangeStatus, onTogglePicker, onOpen, onRemove, onDismiss, providersById }: TitleCardProps) {
  const addedStatus =
    status === 'WANT_TO_WATCH' || status === 'WATCHING' || status === 'WATCHED' ? status : null
  const badgeProviders = (providersById && title.providerIds ? title.providerIds : [])
    .map(id => providersById?.get(id))
    .filter((p): p is WatchProvider => p != null)
    .slice(0, 3)
  return (
    <article
      className="title-card title-card-clickable"
      role="button"
      tabIndex={0}
      onClick={() => onOpen(title)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen(title)
        }
      }}
      aria-label={`View details for ${title.name}`}
    >
      {onDismiss && (
        <button
          className="title-dismiss-btn"
          onClick={(e) => { e.stopPropagation(); onDismiss(title) }}
          aria-label={`Not interested in ${title.name}`}
          title="Not interested"
        >
          ✕
        </button>
      )}
      {title.posterUrl ? (
        <img className="title-poster" src={title.posterUrl} alt={title.name} loading="lazy" />
      ) : (
        <div className="title-poster title-poster-empty" />
      )}
      {badgeProviders.length > 0 && (
        <div className="provider-badge-row" aria-label="Streaming on your services">
          {badgeProviders.map(p => (
            p.logoUrl && <img key={p.id} className="provider-badge-logo" src={p.logoUrl} alt={p.name} title={p.name} loading="lazy" />
          ))}
        </div>
      )}
      <div className="title-card-body">
        <span className="title-type-badge">
          {title.type === 'MOVIE' ? 'Movie' : 'TV Show'}
        </span>
        <p className="title-name">{title.name}</p>
        {title.releaseDate && (
          <p className="title-year">{new Date(title.releaseDate).getFullYear()}</p>
        )}
        {addedStatus ? (
          isPicking ? (
            <StatusPicker
              current={addedStatus}
              onSelect={(s) => onChangeStatus(title, s)}
              onRemove={() => onRemove(title)}
            />
          ) : (
            <div className="discover-action-row">
              <button
                className="discover-added-chip"
                onClick={(e) => { e.stopPropagation(); onTogglePicker(title) }}
                aria-label={`Status: ${STATUS_LABELS[addedStatus]}. Tap to change.`}
              >
                <span className="discover-round-btn discover-round-btn-added" aria-hidden="true">✓</span>
                <span className="discover-added-label">{STATUS_LABELS[addedStatus]}</span>
              </button>
            </div>
          )
        ) : (
          <div className="discover-action-row">
            <button
              className={`discover-round-btn discover-round-btn-add${status === 'error' ? ' discover-round-btn-error' : ''}`}
              disabled={status === 'loading'}
              onClick={(e) => { e.stopPropagation(); onAdd(title, 'WANT_TO_WATCH') }}
              aria-label={status === 'error' ? 'Retry adding to watchlist' : 'Add to watchlist'}
            >
              {status === 'loading' ? '…' : (
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                  <path d="M7 1v12M1 7h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
                </svg>
              )}
            </button>
            {status === 'error' && (
              <span className="discover-added-label discover-error-label">Failed — tap to retry</span>
            )}
          </div>
        )}
      </div>
    </article>
  )
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
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const [cardStatus, setCardStatus] = useState<Record<string, CardStatus>>({})
  const [entryIds, setEntryIds] = useState<Record<string, number>>({})
  const [pickingKey, setPickingKey] = useState<string | null>(null)
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
        setResults(data)
        setSearched(true)
        setCardStatus(prev => {
          const next = { ...prev }
          data.forEach(title => {
            const k = cardKey(title)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.status
            else if (next[k] !== 'loading') delete next[k]
          })
          return next
        })
        setEntryIds(prev => {
          const next = { ...prev }
          data.forEach(title => {
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
  }, [query, api, selectedWatchlistId])

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
  }, [query, api, selectedWatchlistId])

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

  async function handleAddToWatchlist(title: TitleSearchResponse, status: WatchStatus) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setCardStatus(prev => ({ ...prev, [key]: 'loading' }))
    try {
      const titleId = await api.findOrCreateTitle(title)
      const created = await api.addToWatchlist(selectedWatchlistId, titleId, status)
      setEntryIds(prev => ({ ...prev, [key]: created.id }))
      setCardStatus(prev => ({ ...prev, [key]: created.status }))
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: 'error' }))
    }
  }

  function togglePicker(title: TitleSearchResponse) {
    const key = cardKey(title)
    setPickingKey(prev => (prev === key ? null : key))
  }

  async function handleChangeStatus(title: TitleSearchResponse, newStatus: WatchStatus) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setPickingKey(null)
    const entryId = entryIds[key]
    const previous = cardStatus[key]
    if (entryId == null || previous === newStatus) return
    setCardStatus(prev => ({ ...prev, [key]: newStatus }))
    try {
      await api.updateWatchlistEntry(selectedWatchlistId, entryId, newStatus)
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: previous }))
    }
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

  async function handleRemove(title: TitleSearchResponse) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setPickingKey(null)
    const entryId = entryIds[key]
    const previous = cardStatus[key]
    if (entryId == null) return
    setCardStatus(prev => {
      const next = { ...prev }
      delete next[key]
      return next
    })
    setEntryIds(prev => {
      const next = { ...prev }
      delete next[key]
      return next
    })
    try {
      await api.removeFromWatchlist(selectedWatchlistId, entryId)
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: previous }))
      setEntryIds(prev => ({ ...prev, [key]: entryId }))
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
            {!isLoading && searched && results.length === 0 && (
              <p className="search-status">No results for &ldquo;{query}&rdquo;.</p>
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
                (a, b) => (SHELF_KIND_ORDER[a.kind] ?? 5) - (SHELF_KIND_ORDER[b.kind] ?? 5)
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
                        <span className="shelf-provider-chip">On your services</span>
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
