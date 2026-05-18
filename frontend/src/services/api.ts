import type { TitleResponse, TitleSearchResponse, WatchlistEntryResponse } from '../types/api'

const BASE_URL = '/api'

export class UnauthorizedError extends Error {
  constructor() {
    super('Unauthorized')
    this.name = 'UnauthorizedError'
  }
}

export interface BackendUser {
  id: number
  email: string
  displayName: string
}

export async function getCurrentUser(token: string): Promise<BackendUser> {
  const response = await fetch(`${BASE_URL}/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Failed to fetch current user: ${response.status}`)
  return response.json() as Promise<BackendUser>
}

export async function searchTitles(
  query: string,
  token: string,
  type?: string,
): Promise<TitleSearchResponse[]> {
  const params = new URLSearchParams({ q: query })
  if (type) params.set('type', type)

  const response = await fetch(`${BASE_URL}/titles/search?${params}`, {
    headers: { Authorization: `Bearer ${token}` },
  })

  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Search failed with status ${response.status}`)

  return response.json() as Promise<TitleSearchResponse[]>
}

export async function findOrCreateTitle(title: TitleSearchResponse, token: string): Promise<number> {
  const createRes = await fetch(`${BASE_URL}/titles`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      externalId: title.externalId,
      externalSource: title.externalSource,
      type: title.type,
      name: title.name,
      overview: title.overview,
      releaseDate: title.releaseDate,
      posterUrl: title.posterUrl,
    }),
  })
  if (createRes.status === 201) return ((await createRes.json()) as TitleResponse).id
  if (createRes.status === 401) throw new UnauthorizedError()
  if (createRes.status === 409) {
    const params = new URLSearchParams({
      externalId: title.externalId,
      externalSource: title.externalSource,
    })
    const findRes = await fetch(`${BASE_URL}/titles?${params}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!findRes.ok) throw new Error('Failed to find existing title')
    const page = (await findRes.json()) as { content: TitleResponse[] }
    if (!page.content.length) throw new Error('Title not found after conflict')
    return page.content[0].id
  }
  throw new Error(`Failed to save title: ${createRes.status}`)
}

export async function getWatchlist(
  userId: number,
  token: string,
): Promise<WatchlistEntryResponse[]> {
  const response = await fetch(`${BASE_URL}/users/${userId}/watchlist?size=200`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Failed to fetch watchlist: ${response.status}`)
  const page = (await response.json()) as { content: WatchlistEntryResponse[] }
  return page.content
}

export async function addToWatchlist(
  userId: number,
  titleId: number,
  token: string,
): Promise<WatchlistEntryResponse> {
  const response = await fetch(`${BASE_URL}/users/${userId}/watchlist`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ titleId, status: 'WANT_TO_WATCH' }),
  })
  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Failed to add to watchlist: ${response.status}`)
  return response.json() as Promise<WatchlistEntryResponse>
}

export async function updateWatchlistEntry(
  userId: number,
  entryId: number,
  status: string,
  token: string,
): Promise<WatchlistEntryResponse> {
  const response = await fetch(`${BASE_URL}/users/${userId}/watchlist/${entryId}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Failed to update watchlist entry: ${response.status}`)
  return response.json() as Promise<WatchlistEntryResponse>
}

export async function removeFromWatchlist(
  userId: number,
  entryId: number,
  token: string,
): Promise<void> {
  const response = await fetch(`${BASE_URL}/users/${userId}/watchlist/${entryId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  })
  if (response.status === 401) throw new UnauthorizedError()
  if (!response.ok) throw new Error(`Failed to remove from watchlist: ${response.status}`)
}
