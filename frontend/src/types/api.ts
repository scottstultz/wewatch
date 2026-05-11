export type TitleType = 'MOVIE' | 'TV'

export interface TitleSearchResponse {
  externalId: string
  externalSource: string
  type: TitleType
  name: string
  overview: string | null
  releaseDate: string | null
  posterUrl: string | null
}
