import { useEffect, useState } from 'react'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import { formatWatchTime } from '../utils/formatDuration'
import type { Stats } from '../types/api'

function StatsPage() {
  const api = useApi()
  const { selectedWatchlist } = useWatchlists()
  const [stats, setStats] = useState<Stats | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!selectedWatchlist) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    api.getStats(selectedWatchlist.id)
      .then(data => { if (!cancelled) setStats(data) })
      .catch(() => { if (!cancelled) setError('Failed to load stats.') })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [api, selectedWatchlist])

  const statValue = (value: string) => isLoading ? '–' : value
  const count = (n: number | undefined) => statValue(String(n ?? 0))

  // Bars are scaled against the top genre, not against the total: a title counts in each
  // of its genres, so the minutes sum to more than totalMinutes and a percentage-of-total
  // bar would be a lie that happens to look tidy.
  const genres = stats?.genres ?? []
  const topGenreMinutes = genres.length > 0 ? genres[0].minutes : 0

  return (
    <div className="page">
      <section className="hero-panel">
        <div className="hero-copy">
          <p className="section-kicker">Stats</p>
          <h2>Everything {selectedWatchlist?.name ?? 'this list'} has watched.</h2>
          {error && <p className="home-error">{error}</p>}
        </div>
        <div className="stats-grid">
          <article className="stat-card">
            <span className="stat-label">Watch time</span>
            <strong className="stat-value">
              {statValue(formatWatchTime(stats?.totalMinutes))}
            </strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Movies finished</span>
            <strong className="stat-value">{count(stats?.moviesFinished)}</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Shows finished</span>
            <strong className="stat-value">{count(stats?.showsFinished)}</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Episodes watched</span>
            <strong className="stat-value">{count(stats?.episodesFinished)}</strong>
          </article>
        </div>
      </section>

      <section className="stack-list">
        <article className="panel">
          <h3>Where the time goes</h3>
          {isLoading ? (
            <p className="panel-empty">Loading…</p>
          ) : genres.length === 0 ? (
            <p className="panel-empty">Finish something and its genres will show up here.</p>
          ) : (
            <>
              <ul className="genre-list">
                {genres.map(genre => (
                  <li key={genre.genreId} className="genre-row">
                    <span className="genre-name">{genre.name}</span>
                    <div className="genre-track">
                      <div
                        className="genre-bar"
                        style={{ width: `${topGenreMinutes > 0 ? (genre.minutes / topGenreMinutes) * 100 : 0}%` }}
                      />
                    </div>
                    <span className="genre-value">{formatWatchTime(genre.minutes)}</span>
                  </li>
                ))}
              </ul>
              {/* A title counts in every genre it carries, so the bars deliberately
                  over-sum. Say so rather than let someone add them up and find a
                  bigger number than the headline. */}
              <p className="genre-footnote">
                A title counts in each of its genres, so these add up to more than the total.
              </p>
            </>
          )}
        </article>

        {/* Only worth saying when it's true — and worth saying plainly, since it is the
            one thing that makes the watch time an underestimate rather than a fact. */}
        {!isLoading && stats != null && stats.itemsMissingRuntime > 0 && (
          <p className="stats-caveat">
            {stats.itemsMissingRuntime} watched {stats.itemsMissingRuntime === 1 ? 'item has' : 'items have'}
            {' '}no runtime on record, so the real total is a little higher.
          </p>
        )}
      </section>
    </div>
  )
}

export default StatsPage
