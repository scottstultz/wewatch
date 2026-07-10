import { useState } from 'react'
import { cardKey } from '../components/TitleCard'
import type { CardStatus } from '../components/TitleCard'
import type { ApiClient } from '../services/api'
import type { TitleSearchResponse, WatchStatus } from '../types/api'

/**
 * Add-to-watchlist state machine shared by every page that renders TitleCards.
 * Each mutation updates optimistically and reverts on failure; the caller gets
 * setCardStatus/setEntryIds so it can seed both from an existing watchlist.
 */
export function useTitleCardActions(api: ApiClient, selectedWatchlistId: number | null) {
  const [cardStatus, setCardStatus] = useState<Record<string, CardStatus>>({})
  const [entryIds, setEntryIds] = useState<Record<string, number>>({})
  const [pickingKey, setPickingKey] = useState<string | null>(null)

  async function handleAddToWatchlist(title: TitleSearchResponse, status: WatchStatus) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setCardStatus(prev => ({ ...prev, [key]: 'loading' }))
    try {
      const titleId = await api.findOrCreateTitle(title)
      const created = await api.addToWatchlist(selectedWatchlistId, titleId, status)
      setEntryIds(prev => ({ ...prev, [key]: created.id }))
      setCardStatus(prev => ({ ...prev, [key]: created.status }))
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: 'error' }))
    }
  }

  function togglePicker(title: TitleSearchResponse) {
    const key = cardKey(title)
    setPickingKey(prev => (prev === key ? null : key))
  }

  async function handleChangeStatus(title: TitleSearchResponse, newStatus: WatchStatus) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setPickingKey(null)
    const entryId = entryIds[key]
    const previous = cardStatus[key]
    if (entryId == null || previous === newStatus) return
    setCardStatus(prev => ({ ...prev, [key]: newStatus }))
    try {
      await api.updateWatchlistEntry(selectedWatchlistId, entryId, newStatus)
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: previous }))
    }
  }

  async function handleRemove(title: TitleSearchResponse) {
    if (!selectedWatchlistId) return
    const key = cardKey(title)
    setPickingKey(null)
    const entryId = entryIds[key]
    const previous = cardStatus[key]
    if (entryId == null) return
    setCardStatus(prev => {
      const next = { ...prev }
      delete next[key]
      return next
    })
    setEntryIds(prev => {
      const next = { ...prev }
      delete next[key]
      return next
    })
    try {
      await api.removeFromWatchlist(selectedWatchlistId, entryId)
    } catch {
      setCardStatus(prev => ({ ...prev, [key]: previous }))
      setEntryIds(prev => ({ ...prev, [key]: entryId }))
    }
  }

  return {
    cardStatus,
    setCardStatus,
    entryIds,
    setEntryIds,
    pickingKey,
    handleAddToWatchlist,
    togglePicker,
    handleChangeStatus,
    handleRemove,
  }
}
