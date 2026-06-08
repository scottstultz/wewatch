import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'
import { getWatchlists } from '../services/api'
import type { WatchlistResponse } from '../types/api'

interface WatchlistContextType {
  watchlists: WatchlistResponse[]
  selectedWatchlistId: number | null
  selectedWatchlist: WatchlistResponse | undefined
  isLoading: boolean
  selectWatchlist: (id: number) => void
  refreshWatchlists: () => Promise<void>
}

const WatchlistContext = createContext<WatchlistContextType | null>(null)

export function WatchlistProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth()
  const [watchlists, setWatchlists] = useState<WatchlistResponse[]>([])
  const [selectedWatchlistId, setSelectedWatchlistId] = useState<number | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const fetchWatchlists = useCallback(async () => {
    if (!token) return
    setIsLoading(true)
    try {
      const data = await getWatchlists(token)
      setWatchlists(data)
      // Default to the user's default watchlist if nothing selected yet
      setSelectedWatchlistId(prev => {
        if (prev && data.some(w => w.id === prev)) return prev
        const defaultList = data.find(w => w.isDefault)
        return defaultList?.id ?? data[0]?.id ?? null
      })
    } catch {
      // Silently fail — pages handle their own error states
    } finally {
      setIsLoading(false)
    }
  }, [token])

  useEffect(() => {
    fetchWatchlists()
  }, [fetchWatchlists])

  const selectedWatchlist = useMemo(
    () => watchlists.find(w => w.id === selectedWatchlistId),
    [watchlists, selectedWatchlistId],
  )

  const selectWatchlist = useCallback((id: number) => {
    setSelectedWatchlistId(id)
  }, [])

  const refreshWatchlists = useCallback(async () => {
    await fetchWatchlists()
  }, [fetchWatchlists])

  return (
    <WatchlistContext.Provider
      value={{
        watchlists,
        selectedWatchlistId,
        selectedWatchlist,
        isLoading,
        selectWatchlist,
        refreshWatchlists,
      }}
    >
      {children}
    </WatchlistContext.Provider>
  )
}

export function useWatchlists(): WatchlistContextType {
  const ctx = useContext(WatchlistContext)
  if (!ctx) throw new Error('useWatchlists must be used within WatchlistProvider')
  return ctx
}
