import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import { UnauthorizedError, getWatchlistEntries } from '../services/api'
import type { WatchlistEntryResponse } from '../types/api'

function HomePage() {
  const { token, signOut } = useAuth()
  const { selectedWatchlist } = useWatchlists()
  const navigate = useNavigate()
  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const handleUnauthorized = useCallback(() => {
    signOut()
    navigate('/sign-in', { replace: true })
  }, [signOut, navigate])

  useEffect(() => {
    if (!token || !selectedWatchlist) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    getWatchlistEntries(selectedWatchlist.id, token)
      .then(data => { if (!cancelled) setEntries(data) })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else setError('Failed to load watchlist data.')
      })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [token, selectedWatchlist, handleUnauthorized])

  const wantToWatchCount = entries.filter(e => e.status === 'WANT_TO_WATCH').length
  const watchingCount = entries.filter(e => e.status === 'WATCHING').length
  const watchedCount = entries.filter(e => e.status === 'WATCHED').length

  const continueWatching = entries
    .filter(e => e.status === 'WATCHING')
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, 4)

  const recentlyAdded = [...entries]
    .sort((a, b) => b.addedAt.localeCompare(a.addedAt))
    .slice(0, 4)

  const statValue = (n: number) => isLoading ? '–' : String(n)

  return (
    <div className="page">
      <section className="hero-panel">
        <div className="hero-copy">
          <p className="section-kicker">Tonight</p>
          <h2>Keep the next watch decision quick.</h2>
          {error && <p className="home-error">{error}</p>}
        </div>
        <div className="stats-grid">
          <article className="stat-card">
            <span className="stat-label">Want to watch</span>
            <strong className="stat-value">{statValue(wantToWatchCount)}</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Watching</span>
            <strong className="stat-value">{statValue(watchingCount)}</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Watched</span>
            <strong className="stat-value">{statValue(watchedCount)}</strong>
          </article>
        </div>
      </section>

      <section className="content-grid">
        <article className="panel">
          <h3>Continue watching</h3>
          {isLoading ? (
            <p className="panel-empty">Loading…</p>
          ) : continueWatching.length === 0 ? (
            <p className="panel-empty">Nothing in progress yet.</p>
          ) : (
            <ul className="title-row-list">
              {continueWatching.map(entry => (
                <li key={entry.id} className="title-row">
                  {entry.posterUrl ? (
                    <img className="title-row-poster" src={entry.posterUrl} alt={entry.name ?? undefined} loading="lazy" />
                  ) : (
                    <div className="title-row-poster title-row-poster-empty" />
                  )}
                  <div className="title-row-body">
                    <span className="title-type-badge">
                      {entry.type === 'MOVIE' ? 'Movie' : entry.type === 'TV' ? 'TV Show' : ''}
                    </span>
                    <p className="title-name">{entry.name}</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </article>

        <article className="panel">
          <h3>Recently added</h3>
          {isLoading ? (
            <p className="panel-empty">Loading…</p>
          ) : recentlyAdded.length === 0 ? (
            <p className="panel-empty">Your watchlist is empty.</p>
          ) : (
            <ul className="title-row-list">
              {recentlyAdded.map(entry => (
                <li key={entry.id} className="title-row">
                  {entry.posterUrl ? (
                    <img className="title-row-poster" src={entry.posterUrl} alt={entry.name ?? undefined} loading="lazy" />
                  ) : (
                    <div className="title-row-poster title-row-poster-empty" />
                  )}
                  <div className="title-row-body">
                    <span className="title-type-badge">
                      {entry.type === 'MOVIE' ? 'Movie' : entry.type === 'TV' ? 'TV Show' : ''}
                    </span>
                    <p className="title-name">{entry.name}</p>
                    <span className={`title-status-badge${entry.status === 'WATCHING' ? ' title-status-badge-watching' : entry.status === 'WATCHED' ? ' title-status-badge-watched' : ''}`}>
                      {entry.status === 'WANT_TO_WATCH' ? 'Want to Watch' : entry.status === 'WATCHING' ? 'Watching' : 'Watched'}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </article>
      </section>
    </div>
  )
}

export default HomePage
