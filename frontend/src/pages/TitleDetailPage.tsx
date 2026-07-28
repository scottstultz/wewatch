import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import StatusPicker, { STATUS_LABELS } from '../components/StatusPicker'
import OverviewCastPanel from '../components/OverviewCastPanel'
import ThumbsRating from '../components/ThumbsRating'
import TitleCard, { cardKey } from '../components/TitleCard'
import WhereToWatch from '../components/WhereToWatch'
import { useTitleCardActions } from '../hooks/useTitleCardActions'
import { formatRuntime } from '../utils/formatDuration'
import type { TitleDetailResponse, TitleRating, TitleSearchResponse, TitleType, WatchStatus } from '../types/api'

type AddState = 'idle' | 'loading' | 'error'

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
  const api = useApi()
  const { watchlists, selectedWatchlistId, selectWatchlist, isLoading: watchlistsLoading } = useWatchlists()

  const type = parseType(typeParam)
  const navState = location.state as { title?: TitleSearchResponse; fromLibrary?: string } | null
  const hint = navState?.title ?? null
  // Set when the user navigated here from the Library — back returns to the same status tab.
  const fromLibrary = navState?.fromLibrary ?? null

  const [detail, setDetail] = useState<TitleDetailResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [addState, setAddState] = useState<AddState>('idle')
  const [entry, setEntry] = useState<{ id: number; status: WatchStatus } | null>(null)
  const [entryLoaded, setEntryLoaded] = useState(false)
  const [picking, setPicking] = useState(false)
  // Thumbs rating (#273): titleId is null until a titles row exists — the
  // first rate resolves one on demand
  const [titleId, setTitleId] = useState<number | null>(null)
  const [myRating, setMyRating] = useState<TitleRating | null>(null)

  // "More Like This" (#358) — lazily fetched only when the user opens the tab.
  const [recommendations, setRecommendations] = useState<TitleSearchResponse[] | null>(null)
  const [recsRequested, setRecsRequested] = useState(false)
  const [recsLoading, setRecsLoading] = useState(false)
  // The recommendation tiles stay hidden until the watchlist has reconciled once,
  // so an optimistic add can't be clobbered by an in-flight reconcile (#305).
  const [seeded, setSeeded] = useState(false)
  const {
    cardStatus,
    setCardStatus,
    setEntryIds,
    pickingKey,
    handleAddToWatchlist,
    togglePicker,
    handleChangeStatus: handleCardChangeStatus,
    handleRemove,
  } = useTitleCardActions(api, selectedWatchlistId)

  useEffect(() => {
    if (!source || !externalId || !type) {
      if (!type) setError('Unknown title type.')
      setIsLoading(false)
      return
    }
    let cancelled = false
    setIsLoading(true)
    setError(null)
    // A "More Like This" tile navigates to this same route (#406) — React
    // Router reuses this mounted page rather than remounting it, so the
    // previous title's detail/recommendations must be cleared by hand or the
    // panel keeps showing them. Nulling `detail` also drops the OverviewCastPanel
    // instance (gated on `detail &&` below) so its tab state resets instead of
    // pointing at a section the new title doesn't have.
    setDetail(null)
    setRecommendations(null)
    setRecsRequested(false)
    setRecsLoading(false)
    window.scrollTo(0, 0)

    api.getTitleDetail(source, externalId, type)
      .then(data => {
        if (!cancelled) {
          setDetail(data)
          setTitleId(data.titleId)
          setMyRating(data.myRating)
          setIsLoading(false)
        }
      })
      .catch(() => {
        if (cancelled) return
        setError('Failed to load title details.')
        setIsLoading(false)
      })

    return () => { cancelled = true }
  }, [api, source, externalId, type])

  // Look up whether this title is already on the selected watchlist, and seed the
  // "More Like This" grid's add/status map from the *same* fetch (#358) — one call
  // drives both the single-title button and the recommendation tiles. The tiles
  // stay gated on `seeded` until this reconcile lands once (#305).
  useEffect(() => {
    if (!source || !externalId || watchlistsLoading) return
    // No list chosen yet: nothing to reconcile, but still paint tiles as "+".
    if (!selectedWatchlistId) { setSeeded(true); return }
    let cancelled = false
    setEntry(null)
    setEntryLoaded(false)
    setPicking(false)

    api.getWatchlistEntries(selectedWatchlistId)
      .then(entries => {
        if (cancelled) return
        const match = entries.find(
          e => e.externalSource === source && e.externalId === externalId,
        )
        setEntry(match ? { id: match.id, status: match.status } : null)

        // Seed every entry on the list into the grid map. We never delete keys
        // here (unlike PersonPage, which scopes to a known candidate list), so an
        // add still in flight is always preserved.
        setCardStatus(prev => {
          const next = { ...prev }
          entries.forEach(e => { next[`${e.externalSource}-${e.externalId}`] = e.status })
          return next
        })
        setEntryIds(prev => {
          const next = { ...prev }
          entries.forEach(e => { next[`${e.externalSource}-${e.externalId}`] = e.id })
          return next
        })
      })
      .catch(() => { /* treat as not added */ })
      .finally(() => { if (!cancelled) { setEntryLoaded(true); setSeeded(true) } })

    return () => { cancelled = true }
  }, [api, selectedWatchlistId, source, externalId, watchlistsLoading, setCardStatus, setEntryIds])

  // Lazy fetch (#358): only once the user opens the tab (recsRequested), and only
  // once per mount — the result is held in state so toggling doesn't refetch.
  useEffect(() => {
    if (!recsRequested || !detail || recommendations !== null) return
    let cancelled = false
    setRecsLoading(true)

    api.getRecommendations(detail.type, detail.externalId)
      .then(recs => { if (!cancelled) setRecommendations(recs) })
      .catch(() => { if (!cancelled) setRecommendations([]) })
      .finally(() => { if (!cancelled) setRecsLoading(false) })

    return () => { cancelled = true }
  }, [recsRequested, detail, recommendations, api])

  const showMoreLikeThis = useCallback(() => setRecsRequested(true), [])

  async function handleAdd(status: WatchStatus) {
    if (!selectedWatchlistId || !detail) return
    setAddState('loading')
    try {
      const titleId = await api.findOrCreateTitle(detail)
      const created = await api.addToWatchlist(selectedWatchlistId, titleId, status)
      setEntry({ id: created.id, status: created.status })
      setAddState('idle')
    } catch {
      setAddState('error')
    }
  }

  // Optimistic thumb flip with revert (#273). Rating a never-resolved title
  // creates its titles row first — same server-managed resolve as adding.
  async function handleRate(rating: TitleRating | null) {
    if (!detail) return
    const previous = myRating
    setMyRating(rating)
    try {
      const id = titleId ?? (await api.findOrCreateTitle(detail))
      if (titleId == null) setTitleId(id)
      if (rating) await api.rateTitle(id, rating)
      else await api.clearTitleRating(id)
    } catch {
      setMyRating(previous)
    }
  }

  async function handleChangeStatus(newStatus: WatchStatus) {
    if (!selectedWatchlistId || !entry) return
    setPicking(false)
    if (newStatus === entry.status) return
    const previous = entry
    setEntry({ ...entry, status: newStatus })
    try {
      await api.updateWatchlistEntry(selectedWatchlistId, entry.id, newStatus)
    } catch {
      setEntry(previous)
    }
  }

  function openTitle(title: TitleSearchResponse) {
    navigate(
      `/title/${title.type.toLowerCase()}/${title.externalSource}/${title.externalId}`,
      { state: { title } },
    )
  }

  // The tab body is always passed to the panel so the tab is present (lazy) —
  // the fetch fires only when it's opened. Gated on `seeded` per #305.
  function renderMoreLikeThis() {
    if (!seeded || recsLoading || recommendations === null) {
      return <p className="search-status">Loading…</p>
    }
    if (recommendations.length === 0) {
      return <p className="search-status">No similar titles to show.</p>
    }
    return (
      <div className="title-grid">
        {recommendations.map(rec => (
          <TitleCard
            key={cardKey(rec)}
            title={rec}
            status={cardStatus[cardKey(rec)] ?? 'idle'}
            isPicking={pickingKey === cardKey(rec)}
            onAdd={handleAddToWatchlist}
            onChangeStatus={handleCardChangeStatus}
            onTogglePicker={togglePicker}
            onOpen={openTitle}
            onRemove={handleRemove}
          />
        ))}
      </div>
    )
  }

  // Use the navigation hint for instant paint while the full detail loads.
  const name = detail?.name ?? hint?.name ?? ''
  const posterUrl = detail?.posterUrl ?? hint?.posterUrl ?? null
  const displayType = detail?.type ?? hint?.type ?? type
  const year = yearOf(detail?.releaseDate ?? hint?.releaseDate ?? null)
  const runtime = formatRuntime(detail?.runtimeMinutes)

  function goBack() {
    if (fromLibrary) navigate(`/library?status=${fromLibrary}`)
    // location.key is 'default' when this page is the first in-app history
    // entry (opened directly) — there is nothing to go back to (#241).
    else if (location.key === 'default') navigate('/discover')
    else navigate(-1)
  }

  const backLabel = fromLibrary ? 'Library' : 'Back'

  if (error && !detail) {
    return (
      <div className="page">
        <p className="search-status search-status-error">{error}</p>
        <button className="show-detail-back-btn" onClick={goBack}>
          &#8592; {backLabel}
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
            onClick={goBack}
            aria-label={fromLibrary ? 'Back to Library' : 'Back'}
          >
            &#8592; {backLabel}
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
                {runtime && <span>{runtime}</span>}
                {detail?.status && <span>{detail.status}</span>}
                {displayType === 'TV' && detail?.seasonCount != null && (
                  <span>{detail.seasonCount} {detail.seasonCount === 1 ? 'season' : 'seasons'}</span>
                )}
                {detail?.voteAverage != null && detail.voteAverage > 0 && (
                  <span>★ {detail.voteAverage.toFixed(1)}</span>
                )}
              </div>

              {detail && detail.genres.length > 0 && (
                <div className="title-detail-genres">
                  {detail.genres.map(g => (
                    <span key={g} className="title-detail-genre-chip">{g}</span>
                  ))}
                </div>
              )}

              {detail?.trailerUrl && (
              <a
                className="title-detail-trailer"
                href={detail.trailerUrl}
                target="_blank"
                rel="noopener noreferrer"
              >
                ▶ Watch trailer
              </a>
            )}

            {detail && <ThumbsRating rating={myRating} onRate={handleRate} />}

              {entry ? (
                picking ? (
                  <StatusPicker current={entry.status} onSelect={handleChangeStatus} />
                ) : (
                  <div className="discover-action-row">
                    <button
                      className="discover-added-chip"
                      onClick={() => setPicking(true)}
                      aria-label={`Status: ${STATUS_LABELS[entry.status]}. Tap to change.`}
                    >
                      <span className="discover-round-btn discover-round-btn-added" aria-hidden="true">✓</span>
                      <span className="discover-added-label">{STATUS_LABELS[entry.status]}</span>
                    </button>
                  </div>
                )
              ) : (
                <div className="title-detail-add">
                  <span className="discover-picker-label">Add to watchlist as:</span>
                  <StatusPicker
                    current={null}
                    disabled={addState === 'loading' || !selectedWatchlistId || !entryLoaded}
                    onSelect={handleAdd}
                  />
                  {addState === 'error' && (
                    <p className="discover-added-label discover-error-label">Failed to add. Try again.</p>
                  )}
                </div>
              )}

              {watchlists.length > 1 && !entry && (
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

      {detail && (
        <OverviewCastPanel
          overview={detail.overview}
          cast={detail.cast}
          moreLikeThis={renderMoreLikeThis()}
          onShowMoreLikeThis={showMoreLikeThis}
        />
      )}

      <WhereToWatch providers={detail?.watchProviders ?? null} />

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
