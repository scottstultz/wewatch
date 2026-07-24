import type { ReturningEpisode } from '../types/api'

// Per-device marker of which "Returning this week" episodes the user has already seen (#360).
// Client-side only (localStorage, like watchlistStorage.ts) — deliberately per-device, so the
// nav badge nudges each device independently until viewed there. Keyed by watchlist id because
// the returning data is per-watchlist; that also scopes the marker to the selected list.

const keyFor = (watchlistId: number) => `wewatch_returning_seen_${watchlistId}`

// Season + episode are part of the identity so a *newly aired* episode of an already-seen show
// counts as unseen again — which is the whole point of the badge.
export const episodeKey = (episode: ReturningEpisode) =>
  `${episode.entryId}:${episode.seasonNumber}:${episode.episodeNumber}`

export function getSeenReturning(watchlistId: number): Set<string> {
  const raw = localStorage.getItem(keyFor(watchlistId))
  if (!raw) return new Set()
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? new Set(parsed.filter((k): k is string => typeof k === 'string')) : new Set()
  } catch {
    return new Set()
  }
}

// Overwrites (not unions) so the stored set stays bounded to the current window and an episode
// leaving then re-entering is naturally re-surfaced.
export function storeSeenReturning(watchlistId: number, keys: string[]): void {
  localStorage.setItem(keyFor(watchlistId), JSON.stringify(keys))
}
