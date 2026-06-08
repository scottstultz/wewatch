import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import {
  UnauthorizedError,
  bulkMarkSeason,
  getEpisodeProgress,
  getSeasonDetail,
  getSeasons,
  getWatchlistEntries,
  toggleEpisode,
} from '../services/api'
import type {
  EpisodeDetail,
  EpisodeProgress,
  SeasonSummary,
  WatchlistEntryResponse,
} from '../types/api'

function progressKey(season: number, episode: number) {
  return `s${season}e${episode}`
}

function formatAirDate(dateStr: string | null): string | null {
  if (!dateStr) return null
  try {
    const d = new Date(dateStr + 'T00:00:00')
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch {
    return dateStr
  }
}

function isFutureDate(dateStr: string | null): boolean {
  if (!dateStr) return false
  try {
    return new Date(dateStr + 'T00:00:00') > new Date()
  } catch {
    return false
  }
}

function ShowDetailPage() {
  const { entryId: entryIdParam } = useParams<{ entryId: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { token, user, signOut } = useAuth()
  const { selectedWatchlistId, selectedWatchlist } = useWatchlists()

  const watchlistId = Number(searchParams.get('wl')) || selectedWatchlistId
  const entryId = Number(entryIdParam)

  // ── State ──────────────────────────────────────────────────

  const [entry, setEntry] = useState<WatchlistEntryResponse | null>(null)
  const [seasons, setSeasons] = useState<SeasonSummary[]>([])
  const [activeSeason, setActiveSeason] = useState<number | null>(null)
  const [episodes, setEpisodes] = useState<EpisodeDetail[]>([])
  const [progress, setProgress] = useState<Map<string, EpisodeProgress>>(new Map())
  const [allProgress, setAllProgress] = useState<EpisodeProgress[]>([])

  const [isLoadingEntry, setIsLoadingEntry] = useState(true)
  const [isLoadingSeasons, setIsLoadingSeasons] = useState(false)
  const [isLoadingEpisodes, setIsLoadingEpisodes] = useState(false)
  const [togglingEpisodes, setTogglingEpisodes] = useState<Set<string>>(new Set())
  const [isBulkMarking, setIsBulkMarking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleUnauthorized = useCallback(() => {
    signOut()
    navigate('/sign-in', { replace: true })
  }, [signOut, navigate])

  // Determine viewer role — viewers cannot toggle
  const canEdit = useMemo(() => {
    if (!selectedWatchlist || !user) return false
    const member = selectedWatchlist.members.find(m => m.userId === user.id)
    return member?.role === 'OWNER' || member?.role === 'EDITOR'
  }, [selectedWatchlist, user])

  // ── Load entry ─────────────────────────────────────────────

  useEffect(() => {
    if (!token || !watchlistId || !entryId) return
    let cancelled = false
    setIsLoadingEntry(true)
    setError(null)

    getWatchlistEntries(watchlistId, token)
      .then(entries => {
        if (cancelled) return
        const found = entries.find(e => e.id === entryId)
        if (!found) {
          setError('Entry not found in this watchlist.')
          setIsLoadingEntry(false)
          return
        }
        if (found.type !== 'TV') {
          navigate('/library', { replace: true })
          return
        }
        setEntry(found)
        setIsLoadingEntry(false)
      })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else {
          setError('Failed to load entry.')
          setIsLoadingEntry(false)
        }
      })

    return () => { cancelled = true }
  }, [token, watchlistId, entryId, navigate, handleUnauthorized])

  // ── Load seasons once we have the entry ────────────────────

  useEffect(() => {
    if (!token || !entry) return
    let cancelled = false
    setIsLoadingSeasons(true)

    Promise.all([
      getSeasons(entry.titleId, token),
      getEpisodeProgress(watchlistId!, entry.id, token),
    ])
      .then(([seasonList, allProg]) => {
        if (cancelled) return
        setSeasons(seasonList)
        setAllProgress(allProg)
        // Default to first non-specials season, or first season
        const firstReal = seasonList.find(s => s.seasonNumber > 0)
        setActiveSeason(firstReal?.seasonNumber ?? seasonList[0]?.seasonNumber ?? null)
      })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else setError('Failed to load season data.')
      })
      .finally(() => { if (!cancelled) setIsLoadingSeasons(false) })

    return () => { cancelled = true }
  }, [token, entry, watchlistId, handleUnauthorized])

  // ── Load episodes when active season changes ───────────────

  useEffect(() => {
    if (!token || !entry || activeSeason == null || !watchlistId) return
    let cancelled = false
    setIsLoadingEpisodes(true)

    Promise.all([
      getSeasonDetail(entry.titleId, activeSeason, token),
      getEpisodeProgress(watchlistId, entry.id, token, activeSeason),
    ])
      .then(([detail, prog]) => {
        if (cancelled) return
        setEpisodes(detail.episodes)
        const map = new Map<string, EpisodeProgress>()
        for (const p of prog) {
          map.set(progressKey(p.seasonNumber, p.episodeNumber), p)
        }
        setProgress(map)
      })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else setError('Failed to load episodes.')
      })
      .finally(() => { if (!cancelled) setIsLoadingEpisodes(false) })

    return () => { cancelled = true }
  }, [token, entry, activeSeason, watchlistId, handleUnauthorized])

  // ── Overall progress stats ─────────────────────────────────

  const totalEpisodes = useMemo(
    () => seasons.reduce((sum, s) => sum + s.episodeCount, 0),
    [seasons],
  )

  const watchedCount = useMemo(
    () => allProgress.filter(p => p.watched).length,
    [allProgress],
  )

  const progressPct = totalEpisodes > 0 ? Math.round((watchedCount / totalEpisodes) * 100) : 0

  // Season-level watched count for the active season
  const seasonWatchedCount = useMemo(() => {
    let count = 0
    for (const [, p] of progress) {
      if (p.watched) count++
    }
    return count
  }, [progress])

  // ── Toggle episode ─────────────────────────────────────────

  async function handleToggle(seasonNumber: number, episodeNumber: number) {
    if (!token || !watchlistId || !entry) return
    const key = progressKey(seasonNumber, episodeNumber)
    const prev = progress.get(key)
    const wasWatched = prev?.watched ?? false

    // Optimistic update
    setTogglingEpisodes(s => new Set(s).add(key))
    setProgress(map => {
      const next = new Map(map)
      if (prev) {
        next.set(key, { ...prev, watched: !wasWatched, watchedAt: wasWatched ? null : new Date().toISOString() })
      } else {
        next.set(key, {
          id: 0,
          watchlistEntryId: entry.id,
          seasonNumber,
          episodeNumber,
          watched: true,
          watchedAt: new Date().toISOString(),
        })
      }
      return next
    })
    // Optimistic allProgress update
    setAllProgress(all => {
      const idx = all.findIndex(p => p.seasonNumber === seasonNumber && p.episodeNumber === episodeNumber)
      if (idx >= 0) {
        const updated = [...all]
        updated[idx] = { ...updated[idx], watched: !wasWatched }
        return updated
      } else {
        return [...all, {
          id: 0,
          watchlistEntryId: entry.id,
          seasonNumber,
          episodeNumber,
          watched: true,
          watchedAt: new Date().toISOString(),
        }]
      }
    })

    try {
      const result = await toggleEpisode(watchlistId, entry.id, seasonNumber, episodeNumber, token)
      setProgress(map => {
        const next = new Map(map)
        next.set(key, result)
        return next
      })
      setAllProgress(all => {
        const idx = all.findIndex(p => p.seasonNumber === seasonNumber && p.episodeNumber === episodeNumber)
        if (idx >= 0) {
          const updated = [...all]
          updated[idx] = result
          return updated
        }
        return [...all, result]
      })
    } catch (e) {
      // Revert
      setProgress(map => {
        const next = new Map(map)
        if (prev) next.set(key, prev)
        else next.delete(key)
        return next
      })
      setAllProgress(all => {
        if (prev) {
          return all.map(p =>
            p.seasonNumber === seasonNumber && p.episodeNumber === episodeNumber ? prev : p,
          )
        }
        return all.filter(p => !(p.seasonNumber === seasonNumber && p.episodeNumber === episodeNumber))
      })
      if (e instanceof UnauthorizedError) handleUnauthorized()
    } finally {
      setTogglingEpisodes(s => {
        const next = new Set(s)
        next.delete(key)
        return next
      })
    }
  }

  // ── Bulk mark season ───────────────────────────────────────

  async function handleBulkMark(watched: boolean) {
    if (!token || !watchlistId || !entry || activeSeason == null) return
    setIsBulkMarking(true)
    try {
      await bulkMarkSeason(watchlistId, entry.id, activeSeason, watched, token)
      // Re-fetch progress for this season and the overall progress
      const [seasonProg, allProg] = await Promise.all([
        getEpisodeProgress(watchlistId, entry.id, token, activeSeason),
        getEpisodeProgress(watchlistId, entry.id, token),
      ])
      const map = new Map<string, EpisodeProgress>()
      for (const p of seasonProg) {
        map.set(progressKey(p.seasonNumber, p.episodeNumber), p)
      }
      setProgress(map)
      setAllProgress(allProg)
    } catch (e) {
      if (e instanceof UnauthorizedError) handleUnauthorized()
      else setError('Failed to update season.')
    } finally {
      setIsBulkMarking(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────

  if (isLoadingEntry) {
    return (
      <div className="page">
        <p className="search-status">Loading...</p>
      </div>
    )
  }

  if (error && !entry) {
    return (
      <div className="page">
        <p className="search-status search-status-error">{error}</p>
        <button className="show-detail-back-btn" onClick={() => navigate('/library')}>
          Back to Library
        </button>
      </div>
    )
  }

  if (!entry) return null

  const activeSeasonData = seasons.find(s => s.seasonNumber === activeSeason)

  return (
    <div className="page">
      {/* Header */}
      <section className="hero-panel compact-panel">
        <div className="show-detail-header">
          <button
            className="show-detail-back-btn"
            onClick={() => navigate('/library')}
            aria-label="Back to Library"
          >
            &#8592; Library
          </button>

          <div className="show-detail-hero">
            {entry.posterUrl && (
              <img
                className="show-detail-poster"
                src={entry.posterUrl}
                alt={entry.name ?? undefined}
              />
            )}
            <div className="show-detail-info">
              <span className="title-type-badge">TV Show</span>
              <h2 className="show-detail-title">{entry.name}</h2>

              {!isLoadingSeasons && totalEpisodes > 0 && (
                <div className="show-detail-progress-section">
                  <div className="show-detail-progress">
                    <div
                      className="show-detail-progress-bar"
                      style={{ width: `${progressPct}%` }}
                    />
                  </div>
                  <span className="show-detail-progress-text">
                    {watchedCount} / {totalEpisodes} episodes ({progressPct}%)
                  </span>
                </div>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* Season tabs */}
      {isLoadingSeasons ? (
        <p className="search-status">Loading seasons...</p>
      ) : seasons.length > 0 ? (
        <>
          <div className="season-tabs-wrapper">
            <div className="season-tabs">
              {seasons.map(s => (
                <button
                  key={s.seasonNumber}
                  className={`season-tab${s.seasonNumber === activeSeason ? ' season-tab-active' : ''}`}
                  onClick={() => setActiveSeason(s.seasonNumber)}
                >
                  {s.name}
                </button>
              ))}
            </div>
          </div>

          {/* Bulk actions */}
          {canEdit && activeSeasonData && (
            <div className="bulk-season-actions">
              <button
                className="bulk-season-btn"
                disabled={isBulkMarking || isLoadingEpisodes}
                onClick={() => handleBulkMark(true)}
              >
                {isBulkMarking ? 'Updating...' : 'Mark all watched'}
              </button>
              <button
                className="bulk-season-btn bulk-season-btn-secondary"
                disabled={isBulkMarking || isLoadingEpisodes}
                onClick={() => handleBulkMark(false)}
              >
                {isBulkMarking ? 'Updating...' : 'Mark all unwatched'}
              </button>
              {!isLoadingEpisodes && (
                <span className="bulk-season-count">
                  {seasonWatchedCount} / {episodes.length} watched
                </span>
              )}
            </div>
          )}

          {/* Episode list */}
          {isLoadingEpisodes ? (
            <p className="search-status">Loading episodes...</p>
          ) : (
            <section className="episode-list">
              {episodes.map(ep => {
                const key = progressKey(activeSeason!, ep.episodeNumber)
                const prog = progress.get(key)
                const watched = prog?.watched ?? false
                const toggling = togglingEpisodes.has(key)
                const future = isFutureDate(ep.airDate)

                return (
                  <div
                    key={ep.episodeNumber}
                    className={`episode-row${future ? ' episode-row-dimmed' : ''}${watched ? ' episode-row-watched' : ''}`}
                  >
                    <button
                      className={`episode-checkbox${watched ? ' episode-checkbox-checked' : ''}`}
                      disabled={!canEdit || toggling}
                      onClick={() => handleToggle(activeSeason!, ep.episodeNumber)}
                      aria-label={`${watched ? 'Unmark' : 'Mark'} episode ${ep.episodeNumber} as watched`}
                    >
                      {watched ? '✓' : ''}
                    </button>
                    <div className="episode-info">
                      <span className="episode-name">
                        {ep.episodeNumber}. {ep.name}
                      </span>
                      <span className="episode-meta">
                        {formatAirDate(ep.airDate)}
                        {ep.runtimeMinutes != null && ` · ${ep.runtimeMinutes}m`}
                      </span>
                    </div>
                  </div>
                )
              })}
              {episodes.length === 0 && (
                <p className="search-status">No episodes found for this season.</p>
              )}
            </section>
          )}
        </>
      ) : (
        <p className="search-status">No season data available.</p>
      )}

      {error && <p className="search-status search-status-error">{error}</p>}
    </div>
  )
}

export default ShowDetailPage
