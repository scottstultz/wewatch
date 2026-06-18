import { useEffect, useRef, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import {
  UnauthorizedError,
  addToWatchlist,
  findOrCreateTitle,
  getSuggestions,
  getWatchlistEntries,
  searchTitles,
} from '../services/api'
import type { SuggestionShelf, TitleSearchResponse, WatchStatus } from '../types/api'

type AddHandler = (title: TitleSearchResponse, status: WatchStatus) => void

type CardStatus = 'idle' | 'loading' | 'error' | WatchStatus

function cardKey(title: TitleSearchResponse) {
  return `${title.externalSource}-${title.externalId}`
}

interface TitleCardProps {
  title: TitleSearchResponse
  status: CardStatus
  onAdd: AddHandler
}

function TitleCard({ title, status, onAdd }: TitleCardProps) {
  const isAdded = status === 'WANT_TO_WATCH' || status === 'WATCHING' || status === 'WATCHED'
  const addedLabel = status === 'WATCHING' ? 'Watching' : status === 'WATCHED' ? 'Watched' : 'Want to Watch'
  return (
    <article className="title-card">
      {title.posterUrl ? (
        <img className="title-poster" src={title.posterUrl} alt={title.name} loading="lazy" />
      ) : (
        <div className="title-poster title-poster-empty" />
      )}
      <div className="title-card-body">
        <span className="title-type-badge">
          {title.type === 'MOVIE' ? 'Movie' : 'TV Show'}
        </span>
        <p className="title-name">{title.name}</p>
        {title.releaseDate && (
          <p className="title-year">{new Date(title.releaseDate).getFullYear()}</p>
        )}
        {isAdded ? (
          <div className="discover-action-row">
            <span className="discover-round-btn discover-round-btn-added" aria-label="Added to watchlist">✓</span>
            <span className="discover-added-label">{addedLabel}</span>
          </div>
        ) : (
          <div className="discover-action-row">
            <button
              className={`discover-round-btn discover-round-btn-add${status === 'error' ? ' discover-round-btn-error' : ''}`}
              disabled={status === 'loading'}
              onClick={() => onAdd(title, 'WANT_TO_WATCH')}
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
  onAdd: AddHandler
}

function ShelfRow({ titles, cardStatus, onAdd }: ShelfRowProps) {
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
            onAdd={onAdd}
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
  const { token, signOut } = useAuth()
  const { watchlists, selectedWatchlistId, selectWatchlist } = useWatchlists()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const searchInputRef = useRef<HTMLInputElement>(null)
  const [results, setResults] = useState<TitleSearchResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const [cardStatus, setCardStatus] = useState<Record<string, CardStatus>>({})
  const [suggestions, setSuggestions] = useState<SuggestionShelf[]>([])
  const [suggestionsLoading, setSuggestionsLoading] = useState(false)

  // Search effect
  useEffect(() => {
    if (!query.trim()) {
      setResults([])
      setSearched(false)
      return
    }

    const timer = setTimeout(async () => {
      if (!token || !selectedWatchlistId) return
      setIsLoading(true)
      setError(null)
      try {
        const [data, watchlist] = await Promise.all([
          searchTitles(query, token),
          getWatchlistEntries(selectedWatchlistId, token),
        ])
        const watchedKeys = new Map(
          watchlist.map(e => [`${e.externalSource}-${e.externalId}`, e.status])
        )
        setResults(data)
        setSearched(true)
        setCardStatus(prev => {
          const next = { ...prev }
          data.forEach(title => {
            const k = cardKey(title)
            const existingStatus = watchedKeys.get(k)
            if (existingStatus) next[k] = existingStatus
            else if (next[k] !== 'loading') delete next[k]
          })
          return next
        })
      } catch (e) {
        if (e instanceof UnauthorizedError) {
          signOut()
          navigate('/sign-in', { replace: true })
        } else {
          setError('Search failed. Please try again.')
        }
      } finally {
        setIsLoading(false)
      }
    }, 300)

    return () => clearTimeout(timer)
  }, [query, token, selectedWatchlistId, signOut, navigate])

  // Suggestions effect (when query is empty)
  useEffect(() => {
    if (query.trim() || !selectedWatchlistId || !token) {
      setSuggestions([])
      return
    }

    let cancelled = false
    setSuggestionsLoading(true)

    Promise.all([
      getSuggestions(selectedWatchlistId, token),
      getWatchlistEntries(selectedWatchlistId, token),
    ])
      .then(([shelves, entries]) => {
        if (cancelled) return
        setSuggestions(shelves)
        const watchedKeys = new Map(
          entries.map(e => [`${e.externalSource}-${e.externalId}`, e.status])
        )
        setCardStatus(prev => {
          const next = { ...prev }
          shelves.flatMap(s => s.titles).forEach(title => {
            const k = cardKey(title)
            const existingStatus = watchedKeys.get(k)
            if (existingStatus) next[k] = existingStatus
          })
          return next
        })
      })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) {
          signOut()
          navigate('/sign-in', { replace: true })
        }
        // Fail silently — suggestions are non-critical
      })
      .finally(() => {
        if (!cancelled) setSuggestionsLoading(false)
      })

    return () => { cancelled = true }
  }, [query, token, selectedWatchlistId, signOut, navigate])

  async function handleAddToWatchlist(title: TitleSearchResponse, status: WatchStatus) {
    if (!token || !selectedWatchlistId) return
    const key = cardKey(title)
    setCardStatus(prev => ({ ...prev, [key]: 'loading' }))
    try {
      const titleId = await findOrCreateTitle(title, token)
      await addToWatchlist(selectedWatchlistId, titleId, status, token)
      setCardStatus(prev => ({ ...prev, [key]: status }))
    } catch (e) {
      if (e instanceof UnauthorizedError) {
        signOut()
        navigate('/sign-in', { replace: true })
      } else {
        setCardStatus(prev => ({ ...prev, [key]: 'error' }))
      }
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
                    onAdd={handleAddToWatchlist}
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
              const sorted = [...suggestions].sort((a, b) => {
                if (a.kind === 'GENRE_PROFILE' && b.kind !== 'GENRE_PROFILE') return -1
                if (b.kind === 'GENRE_PROFILE' && a.kind !== 'GENRE_PROFILE') return 1
                return 0
              })
              const shownKeys = new Set<string>()
              return sorted.map(shelf => {
                const dedupedTitles = shelf.titles.filter(t => {
                  const k = cardKey(t)
                  if (shownKeys.has(k)) return false
                  shownKeys.add(k)
                  return true
                })
                if (dedupedTitles.length === 0) return null
                return (
                  <div key={shelf.reason} className="suggestion-shelf">
                    <p className="suggestion-shelf-heading">{shelf.reason}</p>
                    <ShelfRow
                      titles={dedupedTitles}
                      cardStatus={cardStatus}
                      onAdd={handleAddToWatchlist}
                    />
                  </div>
                )
              })
            })()}
          </>
        )}
      </section>
    </div>
  )
}

export default DiscoverPage
