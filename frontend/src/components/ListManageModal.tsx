import { useCallback, useEffect, useState } from 'react'
import type { MemberRole, WatchlistResponse } from '../types/api'
import {
  UnauthorizedError,
  addMember,
  deleteWatchlist,
  removeMember,
  setDefaultWatchlist,
  updateMemberRole,
  updateWatchlist,
} from '../services/api'

interface ListManageModalProps {
  watchlist: WatchlistResponse
  isOwner: boolean
  token: string
  onClose: () => void
  onWatchlistUpdated: () => Promise<void>
  onWatchlistDeleted: () => void
  onUnauthorized: () => void
}

const ROLE_LABELS: Record<MemberRole, string> = {
  OWNER: 'Owner',
  EDITOR: 'Editor',
  VIEWER: 'Viewer',
}

function ListManageModal({
  watchlist,
  isOwner,
  token,
  onClose,
  onWatchlistUpdated,
  onWatchlistDeleted,
  onUnauthorized,
}: ListManageModalProps) {
  const [error, setError] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)

  // Invite form
  const [inviteEmail, setInviteEmail] = useState('')

  // Rename form
  const [isRenaming, setIsRenaming] = useState(false)
  const [renameName, setRenameName] = useState(watchlist.name)

  // Close on Escape
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const handleBackdropClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (e.target === e.currentTarget) onClose()
    },
    [onClose],
  )

  async function handleSetDefault() {
    setIsBusy(true)
    setError(null)
    try {
      await setDefaultWatchlist(watchlist.id, token)
      await onWatchlistUpdated()
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError('Failed to set as default.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleRename(e: React.FormEvent) {
    e.preventDefault()
    if (!renameName.trim()) return
    setIsBusy(true)
    setError(null)
    try {
      await updateWatchlist(watchlist.id, renameName.trim(), token)
      await onWatchlistUpdated()
      setIsRenaming(false)
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError('Failed to rename watchlist.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleDelete() {
    if (!confirm('Delete this watchlist? All entries will be permanently removed.')) return
    setIsBusy(true)
    setError(null)
    try {
      await deleteWatchlist(watchlist.id, token)
      onWatchlistDeleted()
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError('Failed to delete watchlist.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault()
    if (!inviteEmail.trim()) return
    setIsBusy(true)
    setError(null)
    try {
      await addMember(watchlist.id, inviteEmail.trim(), token)
      await onWatchlistUpdated()
      setInviteEmail('')
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError(e instanceof Error ? e.message : 'Failed to add member.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleRemoveMember(userId: number) {
    setIsBusy(true)
    setError(null)
    try {
      await removeMember(watchlist.id, userId, token)
      await onWatchlistUpdated()
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError(e instanceof Error ? e.message : 'Failed to remove member.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleRoleChange(userId: number, newRole: MemberRole) {
    setIsBusy(true)
    setError(null)
    try {
      await updateMemberRole(watchlist.id, userId, newRole, token)
      await onWatchlistUpdated()
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      else setError(e instanceof Error ? e.message : 'Failed to update role.')
    } finally {
      setIsBusy(false)
    }
  }

  const isShared = watchlist.type === 'SHARED'

  return (
    <div className="list-manage-overlay" onClick={handleBackdropClick}>
      <div className="list-manage-modal">
        <button className="list-manage-close" onClick={onClose} aria-label="Close">
          &times;
        </button>

        <h3 className="list-manage-title">{watchlist.name}</h3>

        {/* ── List Settings ──────────────────────────────────── */}
        <div className="list-manage-section">
          <h4 className="list-manage-section-title">Settings</h4>

          {/* Default toggle */}
          <div className="list-manage-setting-row">
            <span className="list-manage-setting-label">Default list</span>
            {watchlist.isDefault ? (
              <span className="list-manage-default-active">Current default</span>
            ) : (
              <button
                className="watchlist-create-btn"
                onClick={handleSetDefault}
                disabled={isBusy}
              >
                Set as default
              </button>
            )}
          </div>

          {/* Rename */}
          {isOwner && (
            <div className="list-manage-setting-row">
              <span className="list-manage-setting-label">Name</span>
              {isRenaming ? (
                <form className="watchlist-rename-form" onSubmit={handleRename}>
                  <input
                    className="watchlist-create-input"
                    type="text"
                    value={renameName}
                    onChange={e => setRenameName(e.target.value)}
                    autoFocus
                  />
                  <button className="watchlist-create-btn" type="submit" disabled={isBusy || !renameName.trim()}>
                    Save
                  </button>
                  <button className="watchlist-cancel-btn" type="button" onClick={() => setIsRenaming(false)}>
                    Cancel
                  </button>
                </form>
              ) : (
                <button className="watchlist-rename-btn" onClick={() => { setIsRenaming(true); setRenameName(watchlist.name) }}>
                  Rename
                </button>
              )}
            </div>
          )}
        </div>

        {/* ── Members & Roles ────────────────────────────────── */}
        {isShared && (
          <div className="list-manage-section">
            <h4 className="list-manage-section-title">
              Members ({watchlist.members.length})
            </h4>

            <ul className="member-list">
              {watchlist.members.map(member => (
                <li key={member.userId} className="member-row">
                  <div className="member-info">
                    <span className="member-name">{member.displayName}</span>
                    <span className="member-email">{member.email}</span>
                  </div>

                  {member.role === 'OWNER' ? (
                    <span className="member-role member-role-owner">Owner</span>
                  ) : isOwner ? (
                    <select
                      className="member-role-select"
                      value={member.role}
                      onChange={e => handleRoleChange(member.userId, e.target.value as MemberRole)}
                      disabled={isBusy}
                    >
                      <option value="EDITOR">Editor</option>
                      <option value="VIEWER">Viewer</option>
                    </select>
                  ) : (
                    <span className={`member-role${member.role === 'VIEWER' ? ' member-role-viewer' : ''}`}>
                      {ROLE_LABELS[member.role]}
                    </span>
                  )}

                  {isOwner && member.role !== 'OWNER' && (
                    <button
                      className="title-action-btn title-action-btn-danger"
                      onClick={() => handleRemoveMember(member.userId)}
                      disabled={isBusy}
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>

            {/* Invite */}
            {isOwner && (
              <form className="member-invite-form" onSubmit={handleInvite}>
                <input
                  className="watchlist-create-input"
                  type="email"
                  placeholder="Invite by email..."
                  value={inviteEmail}
                  onChange={e => setInviteEmail(e.target.value)}
                  disabled={isBusy}
                />
                <button
                  className="watchlist-create-btn"
                  type="submit"
                  disabled={isBusy || !inviteEmail.trim()}
                >
                  Invite
                </button>
              </form>
            )}
          </div>
        )}

        {error && <p className="members-error">{error}</p>}

        {/* Delete */}
        {isOwner && isShared && (
          <button className="watchlist-delete-btn" onClick={handleDelete} disabled={isBusy}>
            Delete watchlist
          </button>
        )}
      </div>
    </div>
  )
}

export default ListManageModal
