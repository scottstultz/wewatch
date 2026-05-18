import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { UnauthorizedError, getWatchlist, removeFromWatchlist, updateWatchlistEntry } from '../services/api'
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

const NEXT_STATUS: Partial<Record<WatchStatus, WatchStatus>> = {
  WANT_TO_WATCH: 'WATCHING',
  WATCHING: 'WATCHED',
}

type EntryAction = 'updating' | 'removing'

function badgeClass(status: WatchStatus) {
  if (status === 'WATCHING') return 'title-status-badge title-status-badge-watching'
  if (status === 'WATCHED') return 'title-status-badge title-status-badge-watched'
  return 'title-status-badge'
}

function LibraryPage() {
  const { token, user, signOut } = useAuth()
  const navigate = useNavigate()
  const [entries, setEntries] = useState<WatchlistEntryResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<WatchStatus | 'ALL'>('ALL')
  const [entryActions, setEntryActions] = useState<Record<number, EntryAction>>({})

  const handleUnauthorized = useCallback(() => {
    signOut()
    navigate('/sign-in', { replace: true })
  }, [signOut, navigate])

  useEffect(() => {
    if (!token || !user?.id) return
    let cancelled = false

    setIsLoading(true)
    setError(null)

    getWatchlist(user.id, token)
      .then(data => { if (!cancelled) setEntries(data) })
      .catch(e => {
        if (cancelled) return
        if (e instanceof UnauthorizedError) handleUnauthorized()
        else setError('Failed to load your library. Please try again.')
      })
      .finally(() => { if (!cancelled) setIsLoading(false) })

    return () => { cancelled = true }
  }, [token, user?.id, handleUnauthorized])

  async function handleUpdateStatus(entry: WatchlistEntryResponse, newStatus: WatchStatus) {
    if (!token || !user?.id) return
    setEntryActions(prev => ({ ...prev, [entry.id]: 'updating' }))
    try {
      const updated = await updateWatchlistEntry(user.id, entry.id, newStatus, token)
      setEntries(prev => prev.map(e => e.id === updated.id ? updated : e))
    } catch (e) {
      if (e instanceof UnauthorizedError) handleUnauthorized()
    } finally {
      setEntryActions(prev => { const next = { ...prev }; delete next[entry.id]; return next })
    }
  }

  async function handleRemove(entry: WatchlistEntryResponse) {
    if (!token || !user?.id) return
    setEntryActions(prev => ({ ...prev, [entry.id]: 'removing' }))
    try {
      await removeFromWatchlist(user.id, entry.id, token)
      setEntries(prev => prev.filter(e => e.id !== entry.id))
    } catch (e) {
      if (e instanceof UnauthorizedError) handleUnauthorized()
      else setEntryActions(prev => { const next = { ...prev }; delete next[entry.id]; return next })
    }
  }

  const visible = activeTab === 'ALL' ? entries : entries.filter(e => e.status === activeTab)

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Library</p>
          <h2>Your watchlist.</h2>
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
              ? 'Your library is empty. Head to Discover to add titles.'
              : `No titles with status "${STATUS_LABELS[activeTab as WatchStatus]}".`}
          </p>
        )}

        {visible.length > 0 && (
          <div className="title-grid">
            {visible.map(entry => {
              const action = entryActions[entry.id]
              const nextStatus = NEXT_STATUS[entry.status]
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
                    <span className={badgeClass(entry.status)}>
                      {STATUS_LABELS[entry.status]}
                    </span>
                    <div className="title-action-row">
                      {nextStatus && (
                        <button
                          className="title-action-btn"
                          disabled={!!action}
                          onClick={() => handleUpdateStatus(entry, nextStatus)}
                        >
                          {action === 'updating' ? '…' : `Mark ${STATUS_LABELS[nextStatus]}`}
                        </button>
                      )}
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
