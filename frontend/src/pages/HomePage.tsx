import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import type { WatchlistEntryResponse } from '../types/api'

interface TileRowProps {
  entry: WatchlistEntryResponse
  onClick: () => void
  showStatusBadge?: boolean
}

function TileRow({ entry, onClick, showStatusBadge }: TileRowProps) {
  return (
    <li
      className="title-row title-row-clickable"
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } }}
    >
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
        {showStatusBadge && (
          <span className={`title-status-badge${entry.status === 'WATCHING' ? ' title-status-badge-watching' : entry.status === 'WATCHED' ? ' title-status-badge-watched' : ''}`}>
            {entry.status === 'WANT_TO_WATCH' ? 'Want to Watch' : entry.status === 'WATCHING' ? 'Watching' : 'Watched'}
          </span>
        )}
      </div>
    </li>
  )
}

function HomePage() {
  const api = useApi()
  const { selectedWatchlist } = useWatchlists()
  const navigate = useNavigate()
  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!selectedWatchlist) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    api.getWatchlistEntries(selectedWatchlist.id)
      .then(data => { if (!cancelled) setEntries(data) })
      .catch(() => { if (!cancelled) setError('Failed to load watchlist data.') })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [api, selectedWatchlist])

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

  function handleTileClick(entry: WatchlistEntryResponse) {
    if (entry.type === 'TV' && selectedWatchlist) {
      navigate(`/library/${entry.id}?wl=${selectedWatchlist.id}`)
    } else {
      navigate(`/library?status=${entry.status}`)
    }
  }

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
                <TileRow key={entry.id} entry={entry} onClick={() => handleTileClick(entry)} />
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
                <TileRow key={entry.id} entry={entry} onClick={() => handleTileClick(entry)} showStatusBadge />
              ))}
            </ul>
          )}
        </article>
      </section>
    </div>
  )
}

export default HomePage
