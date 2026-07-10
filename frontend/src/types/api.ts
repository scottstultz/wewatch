export type TitleType = 'MOVIE' | 'TV'
export type WatchStatus = 'WANT_TO_WATCH' | 'WATCHING' | 'WATCHED'
// Thumbs up/down (#273) — deliberately binary; the suggestion algorithm
// only needs sign, not magnitude
export type TitleRating = 'UP' | 'DOWN'
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
  // Which of the viewer's streaming services carry this title (#270); only
  // populated on suggestion shelves, null/undefined elsewhere (= unknown)
  providerIds?: number[] | null
}

// ── Watch providers (#270) ─────────────────────────────────

export interface WatchProvider {
  id: number
  name: string
  logoUrl: string | null
  displayPriority: number
}

export interface WatchRegion {
  code: string
  name: string
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
  // The caller's own thumbs rating (#273) — personal, not the entry's
  myRating: TitleRating | null
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

// One top-billed cast member on a title's detail page (#295)
export interface CastMember {
  id: number
  name: string
  // Blank for some TMDB credits
  character: string | null
  // Null when TMDB has no headshot — the UI renders a silhouette placeholder
  profileUrl: string | null
}

export interface TitleDetailResponse {
  externalId: string
  externalSource: string
  type: TitleType
  name: string
  overview: string | null
  releaseDate: string | null
  posterUrl: string | null
  status: string | null
  genres: string[]
  voteAverage: number | null
  voteCount: number | null
  seasonCount: number | null
  seasons: SeasonSummary[] | null
  // Where the title streams (flatrate) in the caller's watch region (#270)
  watchRegion: string | null
  watchProviders: WatchProvider[] | null
  // Internal title id when a titles row exists (#273) — the ratings API is
  // keyed on it; null for titles nobody has resolved yet
  titleId: number | null
  // The caller's thumbs rating; null when unrated or no title row
  myRating: TitleRating | null
  // Top-billed cast in TMDB billing order (#295); empty when TMDB has no credits
  cast: CastMember[] | null
}

// ── People (#305) ──────────────────────────────────────────

export interface PersonDetailResponse {
  id: number
  name: string
  biography: string | null
  // Null when TMDB has no headshot — the UI renders a silhouette placeholder
  profileUrl: string | null
  knownForDepartment: string | null
  birthday: string | null
  placeOfBirth: string | null
  // Acting credits across movies and TV, deduped and most popular first
  credits: TitleSearchResponse[]
}

export type ShelfKind =
  | 'PER_SEED'
  | 'FINISHED_SEED'
  | 'MORE_PICKS'
  | 'GENRE_PROFILE'
  | 'NEW_RELEASES'
  | 'HIDDEN_GEMS'
  | 'TRENDING'
  | 'PERSON'
  | 'KEYWORD'
  | 'FRANCHISE'

export interface SuggestionShelf {
  reason: string
  titles: TitleSearchResponse[]
  kind: ShelfKind
  // true when the shelf's feed was restricted to the viewer's streaming
  // services (#270) — every title on it is streamable
  providerFiltered: boolean
}

export interface EpisodeProgress {
  id: number
  watchlistEntryId: number
  seasonNumber: number
  episodeNumber: number
  watched: boolean
  watchedAt: string | null
}
