export type TitleType = 'MOVIE' | 'TV'
export type WatchStatus = 'WANT_TO_WATCH' | 'WATCHING' | 'WATCHED'
export type WatchlistType = 'PERSONAL' | 'SHARED'
export type MemberRole = 'OWNER' | 'EDITOR' | 'VIEWER'

export interface TitleSearchResponse {
  externalId: string
  externalSource: string
  type: TitleType
  name: string
  overview: string | null
  releaseDate: string | null
  posterUrl: string | null
}

export interface TitleResponse {
  id: number
  externalId: string
  externalSource: string
  type: TitleType
  name: string
  overview: string | null
  releaseDate: string | null
  posterUrl: string | null
}

export interface WatchlistMemberResponse {
  userId: number
  email: string
  displayName: string
  role: MemberRole
  joinedAt: string
}

export interface WatchlistResponse {
  id: number
  name: string
  type: WatchlistType
  createdAt: string
  updatedAt: string
  members: WatchlistMemberResponse[]
  isDefault: boolean
}

export interface EpisodeProgressSummary {
  watchedCount: number
  lastWatchedSeason: number | null
  lastWatchedEpisode: number | null
  nextSeason: number | null
  nextEpisode: number | null
  nextEpisodeName: string | null
  nextAirDate: string | null
  nextRuntimeMinutes: number | null
  showStatus: string | null
}

export interface WatchlistEntryResponse {
  id: number
  watchlistId: number
  addedByUserId: number | null
  titleId: number
  externalId: string
  externalSource: string
  name: string | null
  type: TitleType | null
  posterUrl: string | null
  status: WatchStatus
  addedAt: string
  updatedAt: string
  startedAt: string | null
  completedAt: string | null
  episodeProgress: EpisodeProgressSummary | null
}

// ── Season / Episode types ──────────────────────────────────

export interface SeasonSummary {
  seasonNumber: number
  name: string
  episodeCount: number
  posterUrl: string | null
  airDate: string | null
}

export interface SeasonDetail {
  seasonNumber: number
  name: string
  overview: string | null
  posterUrl: string | null
  episodes: EpisodeDetail[]
}

export interface EpisodeDetail {
  episodeNumber: number
  name: string
  overview: string | null
  airDate: string | null
  stillUrl: string | null
  runtimeMinutes: number | null
}

export interface EpisodeProgress {
  id: number
  watchlistEntryId: number
  seasonNumber: number
  episodeNumber: number
  watched: boolean
  watchedAt: string | null
}
