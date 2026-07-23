import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useApi } from './AuthContext'
import { useWatchlists } from './WatchlistContext'
import { episodeKey, getSeenReturning, storeSeenReturning } from '../services/returningSeenStorage'
import type { ReturningEpisode } from '../types/api'

// Single source of truth for "Returning this week" (#321) and the unseen-count badge (#360).
// The nav renders the count from anywhere; HomePage clears it via markAllSeen when the panel is
// viewed. HomePage reads `returning` from here too, so the endpoint is fetched once.

interface ReturningNotificationsContextType {
  returning: ReturningEpisode[]
  unseenCount: number
  markAllSeen: () => void
}

const ReturningNotificationsContext = createContext<ReturningNotificationsContextType | null>(null)

export function ReturningNotificationsProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const { selectedWatchlist } = useWatchlists()
  const [returning, setReturning] = useState<ReturningEpisode[]>([])
  const [seen, setSeen] = useState<Set<string>>(new Set())

  useEffect(() => {
    if (!selectedWatchlist) {
      setReturning([])
      setSeen(new Set())
      return
    }
    let cancelled = false
    const watchlistId = selectedWatchlist.id
    setSeen(getSeenReturning(watchlistId))
    // Fault-tolerant like the #321 panel: a rejected — or synchronously throwing — fetch drops the
    // badge rather than crashing the shell the nav lives in. Promise.resolve() folds a synchronous
    // throw into the .catch.
    Promise.resolve()
      .then(() => api.getReturningEpisodes(watchlistId))
      .then(list => { if (!cancelled) setReturning(list) })
      .catch(() => { if (!cancelled) setReturning([]) })
    return () => { cancelled = true }
  }, [api, selectedWatchlist])

  const unseenCount = useMemo(
    () => returning.filter(episode => !seen.has(episodeKey(episode))).length,
    [returning, seen],
  )

  // Marks the provider's full list (not HomePage's entry-filtered rows), so a row transiently
  // missing its entry can't strand the badge.
  const markAllSeen = useCallback(() => {
    if (!selectedWatchlist) return
    const keys = returning.map(episodeKey)
    storeSeenReturning(selectedWatchlist.id, keys)
    setSeen(new Set(keys))
  }, [selectedWatchlist, returning])

  return (
    <ReturningNotificationsContext.Provider value={{ returning, unseenCount, markAllSeen }}>
      {children}
    </ReturningNotificationsContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useReturningNotifications(): ReturningNotificationsContextType {
  const ctx = useContext(ReturningNotificationsContext)
  if (!ctx) throw new Error('useReturningNotifications must be used within ReturningNotificationsProvider')
  return ctx
}
