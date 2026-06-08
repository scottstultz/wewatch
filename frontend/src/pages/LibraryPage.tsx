import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import WatchlistDropdown from '../components/WatchlistDropdown'
import ListManageModal from '../components/ListManageModal'
import {
  UnauthorizedError,
  createWatchlist,
  getWatchlistEntries,
  removeFromWatchlist,
  updateWatchlistEntry,
} from '../services/api'
import type { WatchlistEntryResponse, WatchStatus } from '../types/api'

const STATUS_LABELS: Record<WatchStatus, string> = {
  WANT_TO_WATCH: 'Want to Watch',
  WATCHING: 'Watching',
  WATCHED: 'Watched',
}

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

function LibraryPage() {
  const { token, user, signOut } = useAuth()
  const {
    watchlists,
    selectedWatchlistId,
    selectedWatchlist,
    isLoading: watchlistsLoading,
    selectWatchlist,
    refreshWatchlists,
  } = useWatchlists()
  const navigate = useNavigate()

  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<WatchStatus | 'ALL'>('ALL')
  const [entryActions, setEntryActions] = useState<Record<number, EntryAction>>({})
  const [pickingEntry, setPickingEntry] = useState<number | null>(null)

  // Create watchlist form state
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [newWatchlistName, setNewWatchlistName] = useState('')
  const [isCreating, setIsCreating] = useState(false)

  // Manage modal state
  const [showManageModal, setShowManageModal] = useState(false)

  const handleUnauthorized = useCallback(() => {
    signOut()
    navigate('/sign-in', { replace: true })
  }, [signOut, navigate])

  // Fetch entries when selected watchlist changes
  useEffect(() => {
    if (!token || !selectedWatchlistId) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    getWatchlistEntries(selectedWatchlistId, token)
      .then(data => { if (!cancelled) setEntries(data) })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else setError('Failed to load your library. Please try again.')
      })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [token, selectedWatchlistId, handleUnauthorized])

  // Close manage modal when switching watchlists
  useEffect(() => {
    setShowManageModal(false)
  }, [selectedWatchlistId])

  async function handleUpdateStatus(entry: WatchlistEntryResponse, newStatus: WatchStatus) {
    if (!token || !selectedWatchlistId) return
    const previousStatus = entry.status
    setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, status: newStatus } : e))
    setEntryActions(prev => ({ ...prev, [entry.id]: 'updating' }))
    try {
      const updated = await updateWatchlistEntry(selectedWatchlistId, entry.id, newStatus, token)
      setEntries(prev => prev.map(e => e.id === updated.id ? updated : e))
    } catch (e) {
      setEntries(prev => prev.map(e => e.id === entry.id ? { ...e, status: previousStatus } : e))
      if (e instanceof UnauthorizedError) handleUnauthorized()
      else setError('Failed to update status. Please try again.')
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

  async function handleRemove(entry: WatchlistEntryResponse) {
    if (!token || !selectedWatchlistId) return
    setEntryActions(prev => ({ ...prev, [entry.id]: 'removing' }))
    try {
      await removeFromWatchlist(selectedWatchlistId, entry.id, token)
      setEntries(prev => prev.filter(e => e.id !== entry.id))
    } catch (e) {
      if (e instanceof UnauthorizedError) handleUnauthorized()
      else setEntryActions(prev => { const next = { ...prev }; delete next[entry.id]; return next })
    }
  }

  async function handleCreateWatchlist(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !newWatchlistName.trim()) return
    setIsCreating(true)
    try {
      const created = await createWatchlist(newWatchlistName.trim(), token)
      await refreshWatchlists()
      selectWatchlist(created.id)
      setNewWatchlistName('')
      setShowCreateForm(false)
    } catch (err) {
      if (err instanceof UnauthorizedError) handleUnauthorized()
      else setError('Failed to create watchlist.')
    } finally {
      setIsCreating(false)
    }
  }

  const isOwner = selectedWatchlist?.members.some(
    m => m.userId === user?.id && m.role === 'OWNER'
  )

  const visible = activeTab === 'ALL' ? entries : entries.filter(e => e.status === activeTab)

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
          {showManageModal && selectedWatchlist && token && (
            <ListManageModal
              watchlist={selectedWatchlist}
              isOwner={!!isOwner}
              token={token}
              onClose={() => setShowManageModal(false)}
              onWatchlistUpdated={refreshWatchlists}
              onWatchlistDeleted={async () => {
                await refreshWatchlists()
                setShowManageModal(false)
              }}
              onUnauthorized={handleUnauthorized}
            />
          )}

          {/* Status tabs */}
          <div className="library-tabs">
            {STATUS_TABS.map(tab => (
              <button
                key={tab.value}
                className={`library-tab${activeTab === tab.value ? ' library-tab-active' : ''}`}
                onClick={() => setActiveTab(tab.value)}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </section>

      <section className="stack-list">
        {isLoading && <p className="search-status">Loading…</p>}
        {error && <p className="search-status search-status-error">{error}</p>}

        {!isLoading && !error && visible.length === 0 && (
          <p className="library-empty">
            {activeTab === 'ALL'
              ? 'This watchlist is empty. Head to Discover to add titles.'
              : `No titles with status "${STATUS_LABELS[activeTab as WatchStatus]}".`}
          </p>
        )}

        {visible.length > 0 && (
          <div className="title-grid">
            {visible.map(entry => {
              const action = entryActions[entry.id]
              return (
                <article key={entry.id} className="title-card">
                  {entry.posterUrl ? (
                    <img
                      className="title-poster"
                      src={entry.posterUrl}
                      alt={entry.name ?? undefined}
                      loading="lazy"
                    />
                  ) : (
                    <div className="title-poster title-poster-empty" />
                  )}
                  <div className="title-card-body">
                    <span className="title-type-badge">
                      {entry.type === 'MOVIE' ? 'Movie' : entry.type === 'TV' ? 'TV Show' : ''}
                    </span>
                    <p className="title-name">{entry.name}</p>
                    {pickingEntry === entry.id ? (
                      <div className="discover-status-picker">
                        <button
                          className={`discover-status-option discover-status-option-want${entry.status === 'WANT_TO_WATCH' ? ' discover-status-option-current' : ''}`}
                          onClick={() => handleStatusOptionClick(entry, 'WANT_TO_WATCH')}
                        >
                          {entry.status === 'WANT_TO_WATCH' ? '✓ ' : ''}Want to Watch
                        </button>
                        <button
                          className={`discover-status-option discover-status-option-watching${entry.status === 'WATCHING' ? ' discover-status-option-current' : ''}`}
                          onClick={() => handleStatusOptionClick(entry, 'WATCHING')}
                        >
                          {entry.status === 'WATCHING' ? '✓ ' : ''}Watching
                        </button>
                        <button
                          className={`discover-status-option discover-status-option-watched${entry.status === 'WATCHED' ? ' discover-status-option-current' : ''}`}
                          onClick={() => handleStatusOptionClick(entry, 'WATCHED')}
                        >
                          {entry.status === 'WATCHED' ? '✓ ' : ''}Watched
                        </button>
                      </div>
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
    </div>
  )
}

export default LibraryPage
