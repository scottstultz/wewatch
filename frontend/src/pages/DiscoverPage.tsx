import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import { UnauthorizedError, addToWatchlist, findOrCreateTitle, getWatchlistEntries, searchTitles } from '../services/api'
import type { TitleSearchResponse, WatchStatus } from '../types/api'

type CardStatus = 'idle' | 'loading' | 'error' | WatchStatus

function statusSelectClass(status: WatchStatus) {
  if (status === 'WATCHING') return 'title-status-select title-status-select-watching'
  if (status === 'WATCHED') return 'title-status-select title-status-select-watched'
  return 'title-status-select title-status-select-want'
}

function cardKey(title: TitleSearchResponse) {
  return `${title.externalSource}-${title.externalId}`
}

function DiscoverPage() {
  const { token, signOut } = useAuth()
  const { watchlists, selectedWatchlistId, selectWatchlist } = useWatchlists()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<TitleSearchResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const [cardStatus, setCardStatus] = useState<Record<string, CardStatus>>({})
  const [pendingStatus, setPendingStatus] = useState<Record<string, WatchStatus>>({})

  // Re-run search with "already added" detection when watchlist changes
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
          // Clear stale statuses from previous watchlist
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
          <input
            className="search-input"
            type="search"
            placeholder="Search movies and TV shows…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
          />

          {/* Watchlist picker — choose which list to add to */}
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
        {isLoading && <p className="search-status">Searching…</p>}

        {error && <p className="search-status search-status-error">{error}</p>}

        {!isLoading && searched && results.length === 0 && (
          <p className="search-status">No results for &ldquo;{query}&rdquo;.</p>
        )}

        {results.length > 0 && (
          <div className="title-grid">
            {results.map((title) => {
              const key = cardKey(title)
              const status = cardStatus[key] ?? 'idle'
              const pending = pendingStatus[key] ?? 'WANT_TO_WATCH'
              const isAdded = status === 'WANT_TO_WATCH' || status === 'WATCHING' || status === 'WATCHED'
              return (
                <article key={key} className="title-card">
                  {title.posterUrl ? (
                    <img
                      className="title-poster"
                      src={title.posterUrl}
                      alt={title.name}
                      loading="lazy"
                    />
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
                      <span className={`title-status-badge${status === 'WATCHING' ? ' title-status-badge-watching' : status === 'WATCHED' ? ' title-status-badge-watched' : ''}`}>
                        {status === 'WANT_TO_WATCH' ? 'Want to Watch' : status === 'WATCHING' ? 'Watching' : 'Watched'}
                      </span>
                    ) : (
                      <div className="title-add-row">
                        <select
                          className={statusSelectClass(pending)}
                          value={pending}
                          disabled={status === 'loading'}
                          onChange={(e) => setPendingStatus(prev => ({ ...prev, [key]: e.target.value as WatchStatus }))}
                        >
                          <option value="WANT_TO_WATCH">Want to Watch</option>
                          <option value="WATCHING">Watching</option>
                          <option value="WATCHED">Watched</option>
                        </select>
                        <button
                          className="title-add-btn"
                          disabled={status === 'loading'}
                          onClick={() => handleAddToWatchlist(title, pending)}
                        >
                          {status === 'loading' ? 'Adding…' : status === 'error' ? 'Retry' : 'Add'}
                        </button>
                      </div>
                    )}
                  </div>
                </article>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}

export default DiscoverPage
