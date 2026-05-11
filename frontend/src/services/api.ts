import type { TitleSearchResponse } from '../types/api'

const BASE_URL = '/api'

export class UnauthorizedError extends Error {
  constructor() {
    super('Unauthorized')
    this.name = 'UnauthorizedError'
  }
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
