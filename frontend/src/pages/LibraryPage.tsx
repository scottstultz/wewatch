import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import {
  UnauthorizedError,
  addMember,
  createWatchlist,
  deleteWatchlist,
  getWatchlistEntries,
  removeMember,
  removeFromWatchlist,
  updateWatchlist,
  updateWatchlistEntry,
} from '../services/api'
import type { WatchlistEntryResponse, WatchlistResponse, WatchStatus } from '../types/api'

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

function statusSelectClass(status: WatchStatus) {
  if (status === 'WATCHING') return 'title-status-select title-status-select-watching'
  if (status === 'WATCHED') return 'title-status-select title-status-select-watched'
  return 'title-status-select title-status-select-want'
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

  // Create watchlist form state
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [newWatchlistName, setNewWatchlistName] = useState('')
  const [isCreating, setIsCreating] = useState(false)

  // Members panel state
  const [showMembers, setShowMembers] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [memberError, setMemberError] = useState<string | null>(null)
  const [isMemberAction, setIsMemberAction] = useState(false)

  // Rename state
  const [isRenaming, setIsRenaming] = useState(false)
  const [renameName, setRenameName] = useState('')

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

  // Close members panel when switching watchlists
  useEffect(() => {
    setShowMembers(false)
    setMemberError(null)
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

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !selectedWatchlistId || !inviteEmail.trim()) return
    setIsMemberAction(true)
    setMemberError(null)
    try {
      await addMember(selectedWatchlistId, inviteEmail.trim(), token)
      await refreshWatchlists()
      setInviteEmail('')
    } catch (err) {
      if (err instanceof UnauthorizedError) handleUnauthorized()
      else setMemberError(err instanceof Error ? err.message : 'Failed to add member.')
    } finally {
      setIsMemberAction(false)
    }
  }

  async function handleRemoveMember(userId: number) {
    if (!token || !selectedWatchlistId) return
    setIsMemberAction(true)
    setMemberError(null)
    try {
      await removeMember(selectedWatchlistId, userId, token)
      await refreshWatchlists()
    } catch (err) {
      if (err instanceof UnauthorizedError) handleUnauthorized()
      else setMemberError(err instanceof Error ? err.message : 'Failed to remove member.')
    } finally {
      setIsMemberAction(false)
    }
  }

  async function handleDeleteWatchlist() {
    if (!token || !selectedWatchlistId) return
    if (!confirm('Delete this watchlist? All entries will be permanently removed.')) return
    try {
      await deleteWatchlist(selectedWatchlistId, token)
      await refreshWatchlists()
      setShowMembers(false)
    } catch (err) {
      if (err instanceof UnauthorizedError) handleUnauthorized()
      else setError('Failed to delete watchlist.')
    }
  }

  async function handleRename(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !selectedWatchlistId || !renameName.trim()) return
    try {
      await updateWatchlist(selectedWatchlistId, renameName.trim(), token)
      await refreshWatchlists()
      setIsRenaming(false)
    } catch (err) {
      if (err instanceof UnauthorizedError) handleUnauthorized()
      else setMemberError('Failed to rename watchlist.')
    }
  }

  const isOwner = selectedWatchlist?.members.some(
    m => m.userId === user?.id && m.role === 'OWNER'
  )
  const isShared = selectedWatchlist?.type === 'SHARED'

  const visible = activeTab === 'ALL' ? entries : entries.filter(e => e.status === activeTab)

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Library</p>
          <h2>{selectedWatchlist?.name ?? 'Your watchlist'}</h2>

          {/* Watchlist selector */}
          {!watchlistsLoading && watchlists.length > 0 && (
            <div className="watchlist-selector">
              {watchlists.map(wl => (
                <button
                  key={wl.id}
                  className={`watchlist-pill${wl.id === selectedWatchlistId ? ' watchlist-pill-active' : ''}`}
                  onClick={() => selectWatchlist(wl.id)}
                >
                  {wl.name}
                </button>
              ))}
              <button
                className="watchlist-pill watchlist-pill-new"
                onClick={() => setShowCreateForm(v => !v)}
              >
                + New
              </button>
            </div>
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

          {/* Members button (shared watchlists owned by caller) */}
          {isShared && isOwner && (
            <button
              className="watchlist-members-toggle"
              onClick={() => {
                setShowMembers(v => !v)
                if (!showMembers && selectedWatchlist) setRenameName(selectedWatchlist.name)
              }}
            >
              {showMembers ? 'Close' : `Members (${selectedWatchlist?.members.length ?? 0})`}
            </button>
          )}

          {/* Members panel */}
          {showMembers && selectedWatchlist && (
            <MembersPanel
              watchlist={selectedWatchlist}
              isOwner={!!isOwner}
              inviteEmail={inviteEmail}
              onInviteEmailChange={setInviteEmail}
              onInvite={handleInvite}
              onRemoveMember={handleRemoveMember}
              onDelete={handleDeleteWatchlist}
              isRenaming={isRenaming}
              renameName={renameName}
              onRenameNameChange={setRenameName}
              onStartRename={() => { setIsRenaming(true); setRenameName(selectedWatchlist.name) }}
              onCancelRename={() => setIsRenaming(false)}
              onRename={handleRename}
              error={memberError}
              disabled={isMemberAction}
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
                    <select
                      className={statusSelectClass(entry.status)}
                      value={entry.status}
                      disabled={!!action}
                      onChange={(e) => handleUpdateStatus(entry, e.target.value as WatchStatus)}
                    >
                      <option value="WANT_TO_WATCH">Want to Watch</option>
                      <option value="WATCHING">Watching</option>
                      <option value="WATCHED">Watched</option>
                    </select>
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

// ── Members panel (extracted for readability) ────────────────

interface MembersPanelProps {
  watchlist: WatchlistResponse
  isOwner: boolean
  inviteEmail: string
  onInviteEmailChange: (value: string) => void
  onInvite: (e: React.FormEvent) => void
  onRemoveMember: (userId: number) => void
  onDelete: () => void
  isRenaming: boolean
  renameName: string
  onRenameNameChange: (value: string) => void
  onStartRename: () => void
  onCancelRename: () => void
  onRename: (e: React.FormEvent) => void
  error: string | null
  disabled: boolean
}

function MembersPanel({
  watchlist,
  isOwner,
  inviteEmail,
  onInviteEmailChange,
  onInvite,
  onRemoveMember,
  onDelete,
  isRenaming,
  renameName,
  onRenameNameChange,
  onStartRename,
  onCancelRename,
  onRename,
  error,
  disabled,
}: MembersPanelProps) {
  return (
    <div className="members-panel">
      {/* Rename */}
      <div className="members-panel-header">
        {isRenaming ? (
          <form className="watchlist-rename-form" onSubmit={onRename}>
            <input
              className="watchlist-create-input"
              type="text"
              value={renameName}
              onChange={e => onRenameNameChange(e.target.value)}
              autoFocus
            />
            <button className="watchlist-create-btn" type="submit" disabled={!renameName.trim()}>
              Save
            </button>
            <button className="watchlist-cancel-btn" type="button" onClick={onCancelRename}>
              Cancel
            </button>
          </form>
        ) : (
          <div className="members-panel-title-row">
            <h4 className="members-panel-title">{watchlist.name}</h4>
            {isOwner && (
              <button className="watchlist-rename-btn" onClick={onStartRename}>
                Rename
              </button>
            )}
          </div>
        )}
      </div>

      {/* Member list */}
      <ul className="member-list">
        {watchlist.members.map(member => (
          <li key={member.userId} className="member-row">
            <div className="member-info">
              <span className="member-name">{member.displayName}</span>
              <span className="member-email">{member.email}</span>
            </div>
            <span className={`member-role${member.role === 'OWNER' ? ' member-role-owner' : ''}`}>
              {member.role === 'OWNER' ? 'Owner' : 'Member'}
            </span>
            {isOwner && member.role !== 'OWNER' && (
              <button
                className="title-action-btn title-action-btn-danger"
                onClick={() => onRemoveMember(member.userId)}
                disabled={disabled}
              >
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>

      {/* Invite */}
      {isOwner && (
        <form className="member-invite-form" onSubmit={onInvite}>
          <input
            className="watchlist-create-input"
            type="email"
            placeholder="Invite by email…"
            value={inviteEmail}
            onChange={e => onInviteEmailChange(e.target.value)}
            disabled={disabled}
          />
          <button
            className="watchlist-create-btn"
            type="submit"
            disabled={disabled || !inviteEmail.trim()}
          >
            Invite
          </button>
        </form>
      )}

      {error && <p className="members-error">{error}</p>}

      {/* Delete */}
      {isOwner && (
        <button className="watchlist-delete-btn" onClick={onDelete} disabled={disabled}>
          Delete watchlist
        </button>
      )}
    </div>
  )
}

export default LibraryPage
