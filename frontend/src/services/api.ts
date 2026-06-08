import type {
  TitleResponse,
  TitleSearchResponse,
  WatchlistEntryResponse,
  WatchlistMemberResponse,
  WatchlistResponse,
} from '../types/api'

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

async function apiFetch(url: string, token: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, ...init?.headers },
  })
  if (response.status === 401) throw new UnauthorizedError()
  return response
}

// ── User ─────────────────────────────────────────────────────

export async function getCurrentUser(token: string): Promise<BackendUser> {
  const response = await apiFetch(`${BASE_URL}/users/me`, token)
  if (!response.ok) throw new Error(`Failed to fetch current user: ${response.status}`)
  return response.json() as Promise<BackendUser>
}

// ── Title search ─────────────────────────────────────────────

export async function searchTitles(
  query: string,
  token: string,
  type?: string,
): Promise<TitleSearchResponse[]> {
  const params = new URLSearchParams({ q: query })
  if (type) params.set('type', type)

  const response = await apiFetch(`${BASE_URL}/titles/search?${params}`, token)
  if (!response.ok) throw new Error(`Search failed with status ${response.status}`)
  return response.json() as Promise<TitleSearchResponse[]>
}

export async function findOrCreateTitle(title: TitleSearchResponse, token: string): Promise<number> {
  const createRes = await apiFetch(`${BASE_URL}/titles`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
  if (createRes.status === 409) {
    const params = new URLSearchParams({
      externalId: title.externalId,
      externalSource: title.externalSource,
    })
    const findRes = await apiFetch(`${BASE_URL}/titles?${params}`, token)
    if (!findRes.ok) throw new Error('Failed to find existing title')
    const page = (await findRes.json()) as { content: TitleResponse[] }
    if (!page.content.length) throw new Error('Title not found after conflict')
    return page.content[0].id
  }
  throw new Error(`Failed to save title: ${createRes.status}`)
}

// ── Watchlist CRUD ───────────────────────────────────────────

export async function getWatchlists(token: string): Promise<WatchlistResponse[]> {
  const response = await apiFetch(`${BASE_URL}/watchlists`, token)
  if (!response.ok) throw new Error(`Failed to fetch watchlists: ${response.status}`)
  return response.json() as Promise<WatchlistResponse[]>
}

export async function createWatchlist(name: string, token: string): Promise<WatchlistResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!response.ok) throw new Error(`Failed to create watchlist: ${response.status}`)
  return response.json() as Promise<WatchlistResponse>
}

export async function updateWatchlist(
  watchlistId: number,
  name: string,
  token: string,
): Promise<WatchlistResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!response.ok) throw new Error(`Failed to update watchlist: ${response.status}`)
  return response.json() as Promise<WatchlistResponse>
}

export async function deleteWatchlist(watchlistId: number, token: string): Promise<void> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}`, token, {
    method: 'DELETE',
  })
  if (!response.ok) throw new Error(`Failed to delete watchlist: ${response.status}`)
}

export async function setDefaultWatchlist(
  watchlistId: number,
  token: string,
): Promise<WatchlistResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/default`, token, {
    method: 'PATCH',
  })
  if (!response.ok) throw new Error(`Failed to set default watchlist: ${response.status}`)
  return response.json() as Promise<WatchlistResponse>
}

// ── Watchlist members ────────────────────────────────────────

export async function addMember(
  watchlistId: number,
  email: string,
  token: string,
): Promise<WatchlistMemberResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/members`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
  if (!response.ok) throw new Error(`Failed to add member: ${response.status}`)
  return response.json() as Promise<WatchlistMemberResponse>
}

export async function removeMember(
  watchlistId: number,
  userId: number,
  token: string,
): Promise<void> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/members/${userId}`, token, {
    method: 'DELETE',
  })
  if (!response.ok) throw new Error(`Failed to remove member: ${response.status}`)
}

// ── Watchlist entries ────────────────────────────────────────

export async function getWatchlistEntries(
  watchlistId: number,
  token: string,
): Promise<WatchlistEntryResponse[]> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/entries?size=200`, token)
  if (!response.ok) throw new Error(`Failed to fetch watchlist entries: ${response.status}`)
  const page = (await response.json()) as { content: WatchlistEntryResponse[] }
  return page.content
}

export async function addToWatchlist(
  watchlistId: number,
  titleId: number,
  status: import('../types/api').WatchStatus,
  token: string,
): Promise<WatchlistEntryResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/entries`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ titleId, status }),
  })
  if (!response.ok) throw new Error(`Failed to add to watchlist: ${response.status}`)
  return response.json() as Promise<WatchlistEntryResponse>
}

export async function updateWatchlistEntry(
  watchlistId: number,
  entryId: number,
  status: string,
  token: string,
): Promise<WatchlistEntryResponse> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}`, token, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
  if (!response.ok) throw new Error(`Failed to update watchlist entry: ${response.status}`)
  return response.json() as Promise<WatchlistEntryResponse>
}

export async function removeFromWatchlist(
  watchlistId: number,
  entryId: number,
  token: string,
): Promise<void> {
  const response = await apiFetch(`${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}`, token, {
    method: 'DELETE',
  })
  if (!response.ok) throw new Error(`Failed to remove from watchlist: ${response.status}`)
}
