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
  // TMDB's genre ids for this title, in that medium's catalog (#384). The backend
  // has always sent these; the type only declares them now that Discover filters
  // search results by genre client-side. Optional rather than required, unlike
  // WatchlistEntryResponse.genreIds (#381) — a required field would have churned
  // every title fixture in the suite for a field most call sites never read.
  genreIds?: number[]
  // Which of the viewer's streaming services carry this title (#270); only
  // populated on suggestion shelves, null/undefined elsewhere (= unknown)
  providerIds?: number[] | null
}

// One person hit from the Discover multi search (#356), rendered in the slim
// "People" row and linking to the person page.
export interface PersonSearchResult {
  id: number
  name: string
  // Null when TMDB has no headshot — the UI renders a silhouette placeholder
  profileUrl: string | null
}

// The Discover search payload (#356): titles and people kept separate so the
// client can render a People row above the title grid rather than interleaving.
export interface TitleSearchResults {
  titles: TitleSearchResponse[]
  people: PersonSearchResult[]
}

// ── Watch providers (#270) ─────────────────────────────────

export interface WatchProvider {
  id: number
  name: string
  logoUrl: string | null
  displayPriority: number
}

// ── Genres (#381) ───────────────────────────────────────────

export interface Genre {
  id: number
  name: string
}

// TMDB's two genre catalogs, kept apart because they genuinely differ: "Action" is
// movie id 28 and "Action & Adventure" is TV id 10759. A merged list would offer
// both with no way to tell which medium each belongs to.
export interface GenreCatalog {
  movie: Genre[]
  tv: Genre[]
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
  // TMDB genre ids from our title cache (#381). Always an array — empty, never absent,
  // when the title isn't cached yet — so a genre filter needs no null handling.
  genreIds: number[]
  // Which of the caller's own configured streaming services carry this title (#392), from
  // the same title cache read as genreIds. Always an array — empty, never absent, when the
  // title isn't cached yet or the caller has no services configured.
  providerIds: number[]
}

// ── Returning this week (#321) ──────────────────────────────

// A show whose next episode airs soon. Note this is independent of watch progress —
// unlike EpisodeProgressSummary.nextAirDate, which is the next *unwatched* episode and
// so sits in the past for anyone behind on a returning show.
export interface ReturningEpisode {
  entryId: number
  externalId: string
  externalSource: string
  showName: string
  posterUrl: string | null
  seasonNumber: number
  episodeNumber: number
  episodeName: string | null
  airDate: string
  runtimeMinutes: number | null
}

// ── What can I finish tonight? (#359) ───────────────────────

// One entry that fits a chosen time window. A movie is judged on its own runtime, a
// show on the runtime of the next unwatched episode (episode 1 for a show never
// started). Titles with no known runtime never appear — they can't be judged to fit,
// so the absence of an entry id here means "doesn't fit or can't be measured".
export interface TonightPick {
  entryId: number
  type: TitleType
  runtimeMinutes: number
  nextSeason: number | null
  nextEpisode: number | null
}

// ── Stats (#323) ────────────────────────────────────────────

// Watch stats for a watchlist, not for the signed-in user: episode progress is stored
// per entry and has no user column, so on a shared list these are the household's numbers.
export interface Stats {
  moviesFinished: number
  showsFinished: number
  episodesFinished: number
  totalMinutes: number
  movieMinutes: number
  episodeMinutes: number
  // Watched movies/episodes with no cached runtime: counted above, but contributing no
  // minutes. Says how far the time totals can be trusted.
  itemsMissingRuntime: number
  genres: GenreStat[]
}

// A title counts in every genre it carries, so these sum to more than totalMinutes —
// a shape, not a partition. Hence minutes rather than percentages.
export interface GenreStat {
  genreId: number
  name: string
  minutes: number
  titleCount: number
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
  // Total runtime in minutes for movies; null for TV
  runtimeMinutes: number | null
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
  // A ready-to-open YouTube URL for the trailer (#340); null when TMDB has none
  trailerUrl: string | null
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
  | 'BOTH_WATCH'

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
