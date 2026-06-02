export type TitleType = 'MOVIE' | 'TV'
export type WatchStatus = 'WANT_TO_WATCH' | 'WATCHING' | 'WATCHED'

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
}
