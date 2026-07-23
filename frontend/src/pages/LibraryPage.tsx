import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useApi, useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import WatchlistDropdown from '../components/WatchlistDropdown'
import ListManageModal from '../components/ListManageModal'
import RollTheDiceModal, { MIN_PICKS } from '../components/RollTheDiceModal'
import StatusPicker, { STATUS_LABELS } from '../components/StatusPicker'
import ThumbsRating from '../components/ThumbsRating'
import { formatUpcomingDate } from '../utils/episodeLabels'
import type { TitleRating, WatchlistEntryResponse, WatchStatus } from '../types/api'

const STATUS_TABS: { value: WatchStatus | 'ALL'; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'WANT_TO_WATCH', label: 'Want to Watch' },
  { value: 'WATCHING', label: 'Watching' },
  { value: 'WATCHED', label: 'Watched' },
]

function statusBadgeClass(status: WatchStatus) {
  if (status === 'WATCHING') return 'title-status-badge title-status-badge-watching title-status-badge-btn'
  if (status === 'WATCHED') return 'title-status-badge title-status-badge-watched title-status-badge-btn'
  return 'title-status-badge title-status-badge-btn'
}

type EntryAction = 'updating' | 'removing'

function isFutureDate(dateStr: string | null): boolean {
  if (!dateStr) return false
  try {
    return new Date(dateStr + 'T00:00:00') > new Date()
  } catch {
    return false
  }
}

function episodeProgressLabel(ep: import('../types/api').EpisodeProgressSummary): string {
  if (ep.nextSeason != null && ep.nextEpisode != null) {
    let label = `Up next: S${ep.nextSeason} E${ep.nextEpisode}`
    if (ep.nextEpisodeName) label += ` · ${ep.nextEpisodeName}`
    if (ep.nextRuntimeMinutes) label += ` · ${ep.nextRuntimeMinutes}m`
    return label
  }
  if (ep.watchedCount > 0) {
    const ended = ep.showStatus === 'Ended' || ep.showStatus === 'Canceled'
    const prefix = ended ? 'Series complete' : 'All caught up'
    return `${prefix} · ${ep.watchedCount} watched`
  }
  return 'Episodes'
}

function LibraryPage() {
  const { user } = useAuth()
  const api = useApi()
  const {
    watchlists,
    selectedWatchlistId,
    selectedWatchlist,
    isLoading: watchlistsLoading,
    selectWatchlist,
    refreshWatchlists,
  } = useWatchlists()
  const navigate = useNavigate()

  const [searchParams, setSearchParams] = useSearchParams()
  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const VALID_STATUSES: (WatchStatus | 'ALL')[] = ['ALL', 'WANT_TO_WATCH', 'WATCHING', 'WATCHED']
  const paramStatus = searchParams.get('status') as WatchStatus | 'ALL' | null
  const activeTab: WatchStatus | 'ALL' = paramStatus && VALID_STATUSES.includes(paramStatus) ? paramStatus : 'WATCHING'
  const [entryActions, setEntryActions] = useState<Record<number, EntryAction>>({})
  const [pickingEntry, setPickingEntry] = useState<number | null>(null)
  const [searchQuery, setSearchQuery] = useState('')

  // Create watchlist form state
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [newWatchlistName, setNewWatchlistName] = useState('')
  const [isCreating, setIsCreating] = useState(false)

  // Manage modal state
  const [showManageModal, setShowManageModal] = useState(false)

  // Roll the dice modal state
  const [showDice, setShowDice] = useState(false)

  // Fetch entries when selected watchlist changes
  useEffect(() => {
    if (!selectedWatchlistId) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    api.getWatchlistEntries(selectedWatchlistId)
      .then(data => { if (!cancelled) setEntries(data) })
      .catch(() => { if (!cancelled) setError('Failed to load your library. Please try again.') })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [api, selectedWatchlistId])

  // Close manage modal and clear search when switching watchlists
  useEffect(() => {
    setShowManageModal(false)
    setSearchQuery('')
  }, [selectedWatchlistId])

  async function handleUpdateStatus(entry: WatchlistEntryResponse, newStatus: WatchStatus) {
    if (!selectedWatchlistId) return
    const previousStatus = entry.status
    setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, status: newStatus } : e))
    setEntryActions(prev => ({ ...prev, [entry.id]: 'updating' }))
    try {
      const updated = await api.updateWatchlistEntry(selectedWatchlistId, entry.id, newStatus)
      setEntries(prev => prev.map(e => e.id === updated.id ? updated : e))
    } catch {
      setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, status: previousStatus } : e))
      setError('Failed to update status. Please try again.')
    } finally {
      setEntryActions(prev => { const next = { ...prev }; delete next[entry.id]; return next })
    }
  }

  function handleStatusOptionClick(entry: WatchlistEntryResponse, newStatus: WatchStatus) {
    setPickingEntry(null)
    if (newStatus !== entry.status) {
      handleUpdateStatus(entry, newStatus)
    }
  }

  // Ratings are personal and optimistic (#273): flip the thumb immediately,
  // revert on failure. No role gating — viewers can rate too.
  async function handleRate(entry: WatchlistEntryResponse, rating: TitleRating | null) {
    const previousRating = entry.myRating
    setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, myRating: rating } : e))
    try {
      if (rating) await api.rateTitle(entry.titleId, rating)
      else await api.clearTitleRating(entry.titleId)
    } catch {
      setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, myRating: previousRating } : e))
    }
  }

  async function handleRemove(entry: WatchlistEntryResponse) {
    if (!selectedWatchlistId) return
    setEntryActions(prev => ({ ...prev, [entry.id]: 'removing' }))
    try {
      await api.removeFromWatchlist(selectedWatchlistId, entry.id)
      setEntries(prev => prev.filter(e => e.id !== entry.id))
    } catch {
      setEntryActions(prev => { const next = { ...prev }; delete next[entry.id]; return next })
    }
  }

  async function handleCreateWatchlist(e: React.FormEvent) {
    e.preventDefault()
    if (!newWatchlistName.trim()) return
    setIsCreating(true)
    try {
      const created = await api.createWatchlist(newWatchlistName.trim())
      await refreshWatchlists()
      selectWatchlist(created.id)
      setNewWatchlistName('')
      setShowCreateForm(false)
    } catch {
      setError('Failed to create watchlist.')
    } finally {
      setIsCreating(false)
    }
  }

  const isOwner = selectedWatchlist?.members.some(
    m => m.userId === user?.id && m.role === 'OWNER'
  )

  function openEntry(entry: WatchlistEntryResponse) {
    if (entry.type === 'TV') {
      navigate(`/library/${entry.id}?wl=${selectedWatchlistId}&status=${activeTab}`)
    } else if (entry.type === 'MOVIE') {
      navigate(
        `/title/movie/${entry.externalSource}/${entry.externalId}`,
        {
          state: {
            title: {
              externalId: entry.externalId,
              externalSource: entry.externalSource,
              type: entry.type,
              name: entry.name ?? '',
              overview: null,
              releaseDate: null,
              posterUrl: entry.posterUrl,
            },
            fromLibrary: activeTab,
          },
        },
      )
    }
  }

  const visible = (activeTab === 'ALL' ? entries : entries.filter(e => e.status === activeTab))
    .filter(e => (e.name ?? '').toLowerCase().includes(searchQuery.trim().toLowerCase()))
  const watchingEntries = entries.filter(e => e.status === 'WATCHING')
  const wantToWatchEntries = entries.filter(e => e.status === 'WANT_TO_WATCH')
  const wantToWatchEligible =
    wantToWatchEntries.filter(e => e.type === 'MOVIE').length >= MIN_PICKS
    || wantToWatchEntries.filter(e => e.type === 'TV').length >= MIN_PICKS
  const canRoll =
    (activeTab === 'WATCHING' && watchingEntries.length >= MIN_PICKS)
    || (activeTab === 'WANT_TO_WATCH' && wantToWatchEligible)

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Library</p>
          <h2>{selectedWatchlist?.name ?? 'Your watchlist'}</h2>

          {/* Watchlist selector */}
          {!watchlistsLoading && watchlists.length > 0 && (
            <WatchlistDropdown
              watchlists={watchlists}
              selectedWatchlistId={selectedWatchlistId}
              onSelect={selectWatchlist}
              onCreateNew={() => setShowCreateForm(v => !v)}
            />
          )}

          {/* Create form */}
          {showCreateForm && (
            <form className="watchlist-create-form" onSubmit={handleCreateWatchlist}>
              <input
                className="watchlist-create-input"
                type="text"
                placeholder="Watchlist name…"
                value={newWatchlistName}
                onChange={e => setNewWatchlistName(e.target.value)}
                disabled={isCreating}
                autoFocus
              />
              <button className="watchlist-create-btn" type="submit" disabled={isCreating || !newWatchlistName.trim()}>
                {isCreating ? 'Creating…' : 'Create'}
              </button>
            </form>
          )}

          {/* Manage button */}
          {selectedWatchlist && (
            <button
              className="watchlist-manage-btn"
              onClick={() => setShowManageModal(true)}
            >
              Manage
            </button>
          )}

          {/* List management modal */}
          {showManageModal && selectedWatchlist && (
            <ListManageModal
              watchlist={selectedWatchlist}
              isOwner={!!isOwner}
              onClose={() => setShowManageModal(false)}
              onWatchlistUpdated={refreshWatchlists}
              onWatchlistDeleted={async () => {
                await refreshWatchlists()
                setShowManageModal(false)
              }}
            />
          )}

          {/* Status tabs */}
          <div className="library-tabs">
            {STATUS_TABS.map(tab => (
              <button
                key={tab.value}
                className={`library-tab${activeTab === tab.value ? ' library-tab-active' : ''}`}
                onClick={() => setSearchParams({ status: tab.value }, { replace: true })}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {/* Search */}
          <div className="search-input-wrapper">
            <input
              className="search-input"
              type="search"
              placeholder="Search your library…"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
            />
            {searchQuery && (
              <button
                className="search-clear-btn"
                onClick={() => setSearchQuery('')}
                aria-label="Clear search"
              >
                ✕
              </button>
            )}
          </div>

        </div>
      </section>

      <section className="stack-list">

        {isLoading && <p className="search-status">Loading…</p>}
        {error && <p className="search-status search-status-error">{error}</p>}

        {!isLoading && !error && visible.length === 0 && (
          <p className="library-empty">
            {searchQuery.trim()
              ? `No titles match "${searchQuery.trim()}".`
              : activeTab === 'ALL'
                ? 'This watchlist is empty. Head to Discover to add titles.'
                : activeTab === 'WATCHING'
                  ? 'No titles in progress. Move a title from Want to Watch to start tracking it.'
                  : `No titles with status "${STATUS_LABELS[activeTab as WatchStatus]}".`}
          </p>
        )}

        {visible.length > 0 && (
          <div className="title-grid">
            {visible.map(entry => {
              const action = entryActions[entry.id]
              const isTv = entry.type === 'TV'
              const clickable = entry.type === 'TV' || entry.type === 'MOVIE'
              return (
                <article key={entry.id} className={`title-card${isTv ? ' title-card-tv' : ''}`}>
                  {entry.posterUrl ? (
                    <img
                      className="title-poster"
                      src={entry.posterUrl}
                      alt={entry.name ?? undefined}
                      loading="lazy"
                      onClick={clickable ? () => openEntry(entry) : undefined}
                      style={clickable ? { cursor: 'pointer' } : undefined}
                    />
                  ) : (
                    <div
                      className="title-poster title-poster-empty"
                      onClick={clickable ? () => openEntry(entry) : undefined}
                      style={clickable ? { cursor: 'pointer' } : undefined}
                    />
                  )}
                  <div className="title-card-body">
                    <span className="title-type-badge">
                      {entry.type === 'MOVIE' ? 'Movie' : entry.type === 'TV' ? 'TV Show' : ''}
                    </span>
                    <p className="title-name">{entry.name}</p>
                    {isTv && (
                      <>
                        <button
                          className="title-episodes-link"
                          onClick={() => openEntry(entry)}
                        >
                          {entry.episodeProgress
                            ? episodeProgressLabel(entry.episodeProgress)
                            : 'Episodes'}
                        </button>
                        {entry.episodeProgress?.nextAirDate != null && isFutureDate(entry.episodeProgress.nextAirDate) && (
                          <span className="title-upcoming-date">
                            Airs {formatUpcomingDate(entry.episodeProgress.nextAirDate)}
                          </span>
                        )}
                      </>
                    )}
                    {pickingEntry === entry.id ? (
                      <StatusPicker
                        current={entry.status}
                        onSelect={s => handleStatusOptionClick(entry, s)}
                      />
                    ) : (
                      <button
                        className={statusBadgeClass(entry.status)}
                        disabled={!!action}
                        onClick={() => setPickingEntry(entry.id)}
                        aria-label={`Status: ${STATUS_LABELS[entry.status]}. Tap to change.`}
                      >
                        {action === 'updating' ? '…' : STATUS_LABELS[entry.status]}
                      </button>
                    )}
                    <div className="title-action-row">
                      <ThumbsRating
                        rating={entry.myRating}
                        onRate={r => handleRate(entry, r)}
                      />
                      <button
                        className="title-action-btn title-action-btn-danger"
                        disabled={!!action}
                        onClick={() => handleRemove(entry)}
                      >
                        {action === 'removing' ? '…' : 'Remove'}
                      </button>
                    </div>
                  </div>
                </article>
              )
            })}
          </div>
        )}
      </section>

      {canRoll && (
        <button
          className="flip-fab"
          onClick={() => setShowDice(true)}
          aria-label={
            activeTab === 'WANT_TO_WATCH'
              ? 'Roll the dice — pick a random title from Want to Watch'
              : 'Roll the dice — pick a random show to watch'
          }
          title="Roll the dice"
        >
          🎲
        </button>
      )}

      {showDice && selectedWatchlistId && (
        <RollTheDiceModal
          entries={activeTab === 'WANT_TO_WATCH' ? wantToWatchEntries : watchingEntries}
          watchlistId={selectedWatchlistId}
          wantToWatchMode={activeTab === 'WANT_TO_WATCH'}
          onClose={() => setShowDice(false)}
          onOpenEntry={(entry) => {
            setShowDice(false)
            openEntry(entry)
          }}
        />
      )}
    </div>
  )
}

export default LibraryPage
