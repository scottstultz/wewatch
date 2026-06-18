import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import {
  UnauthorizedError,
  addToWatchlist,
  findOrCreateTitle,
  getTitleDetail,
} from '../services/api'
import type { TitleDetailResponse, TitleSearchResponse, TitleType, WatchStatus } from '../types/api'

type AddState = 'idle' | 'loading' | 'error' | WatchStatus

function parseType(raw: string | undefined): TitleType | null {
  const upper = (raw ?? '').toUpperCase()
  return upper === 'MOVIE' || upper === 'TV' ? upper : null
}

function yearOf(dateStr: string | null): string | null {
  if (!dateStr) return null
  const d = new Date(dateStr)
  return Number.isNaN(d.getTime()) ? null : String(d.getFullYear())
}

function TitleDetailPage() {
  const { type: typeParam, source, externalId } = useParams<{ type: string; source: string; externalId: string }>()
  const location = useLocation()
  const navigate = useNavigate()
  const { token, signOut } = useAuth()
  const { watchlists, selectedWatchlistId, selectWatchlist } = useWatchlists()

  const type = parseType(typeParam)
  const hint = (location.state as { title?: TitleSearchResponse } | null)?.title ?? null

  const [detail, setDetail] = useState<TitleDetailResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [addState, setAddState] = useState<AddState>('idle')

  const handleUnauthorized = () => {
    signOut()
    navigate('/sign-in', { replace: true })
  }

  useEffect(() => {
    if (!token || !source || !externalId || !type) {
      if (!type) setError('Unknown title type.')
      setIsLoading(false)
      return
    }
    let cancelled = false
    setIsLoading(true)
    setError(null)

    getTitleDetail(source, externalId, type, token)
      .then(data => {
        if (!cancelled) {
          setDetail(data)
          setIsLoading(false)
        }
      })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else {
          setError('Failed to load title details.')
          setIsLoading(false)
        }
      })

    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, source, externalId, type])

  async function handleAdd() {
    if (!token || !selectedWatchlistId || !detail) return
    setAddState('loading')
    try {
      const asSearch: TitleSearchResponse = {
        externalId: detail.externalId,
        externalSource: detail.externalSource,
        type: detail.type,
        name: detail.name,
        overview: detail.overview,
        releaseDate: detail.releaseDate,
        posterUrl: detail.posterUrl,
      }
      const titleId = await findOrCreateTitle(asSearch, token)
      await addToWatchlist(selectedWatchlistId, titleId, 'WANT_TO_WATCH', token)
      setAddState('WANT_TO_WATCH')
    } catch (e) {
      if (e instanceof UnauthorizedError) handleUnauthorized()
      else setAddState('error')
    }
  }

  // Use the navigation hint for instant paint while the full detail loads.
  const name = detail?.name ?? hint?.name ?? ''
  const posterUrl = detail?.posterUrl ?? hint?.posterUrl ?? null
  const displayType = detail?.type ?? hint?.type ?? type
  const year = yearOf(detail?.releaseDate ?? hint?.releaseDate ?? null)
  const isAdded = addState === 'WANT_TO_WATCH'

  if (error && !detail) {
    return (
      <div className="page">
        <p className="search-status search-status-error">{error}</p>
        <button className="show-detail-back-btn" onClick={() => navigate(-1)}>
          &#8592; Back
        </button>
      </div>
    )
  }

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="show-detail-header">
          <button
            className="show-detail-back-btn"
            onClick={() => navigate(-1)}
            aria-label="Back"
          >
            &#8592; Back
          </button>

          <div className="show-detail-hero">
            {posterUrl ? (
              <img className="show-detail-poster" src={posterUrl} alt={name} />
            ) : (
              <div className="show-detail-poster title-poster-empty" />
            )}
            <div className="show-detail-info">
              {displayType && (
                <span className="title-type-badge">
                  {displayType === 'MOVIE' ? 'Movie' : 'TV Show'}
                </span>
              )}
              <h2 className="show-detail-title">{name}</h2>

              <div className="title-detail-meta">
                {year && <span>{year}</span>}
                {detail?.status && <span>{detail.status}</span>}
                {displayType === 'TV' && detail?.seasonCount != null && (
                  <span>{detail.seasonCount} {detail.seasonCount === 1 ? 'season' : 'seasons'}</span>
                )}
              </div>

              {detail && detail.genres.length > 0 && (
                <div className="title-detail-genres">
                  {detail.genres.map(g => (
                    <span key={g} className="title-detail-genre-chip">{g}</span>
                  ))}
                </div>
              )}

              {isAdded ? (
                <div className="discover-action-row">
                  <span className="discover-round-btn discover-round-btn-added" aria-label="Added to watchlist">✓</span>
                  <span className="discover-added-label">Want to Watch</span>
                </div>
              ) : (
                <button
                  className={`bulk-season-btn${addState === 'error' ? ' discover-round-btn-error' : ''}`}
                  disabled={addState === 'loading' || !selectedWatchlistId}
                  onClick={handleAdd}
                >
                  {addState === 'loading' ? 'Adding…' : addState === 'error' ? 'Failed — retry' : '+ Add to watchlist'}
                </button>
              )}

              {watchlists.length > 1 && !isAdded && (
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
          </div>
        </div>
      </section>

      {isLoading && <p className="search-status">Loading details…</p>}

      {detail?.overview && (
        <section className="panel">
          <h3>Overview</h3>
          <p>{detail.overview}</p>
        </section>
      )}

      {displayType === 'TV' && detail?.seasons && detail.seasons.length > 0 && (
        <section className="panel">
          <h3>Seasons</h3>
          <ul className="title-detail-season-list">
            {detail.seasons.map(s => (
              <li key={s.seasonNumber} className="title-detail-season-row">
                <span className="title-detail-season-name">{s.name}</span>
                <span className="title-detail-season-count">
                  {s.episodeCount} {s.episodeCount === 1 ? 'episode' : 'episodes'}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

export default TitleDetailPage
