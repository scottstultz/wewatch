const SELECTED_WATCHLIST_KEY = 'wewatch_selected_watchlist'

export function getStoredWatchlistId(): number | null {
  const raw = localStorage.getItem(SELECTED_WATCHLIST_KEY)
  if (raw == null) return null
  const id = Number(raw)
  return Number.isInteger(id) ? id : null
}

export function storeWatchlistId(id: number): void {
  localStorage.setItem(SELECTED_WATCHLIST_KEY, String(id))
}

export function clearStoredWatchlistId(): void {
  localStorage.removeItem(SELECTED_WATCHLIST_KEY)
}
