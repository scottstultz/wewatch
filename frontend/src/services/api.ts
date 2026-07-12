import type {
  EpisodeProgress,
  MemberRole,
  PersonDetailResponse,
  ReturningEpisode,
  SeasonDetail,
  SeasonSummary,
  SuggestionShelf,
  TitleDetailResponse,
  TitleRating,
  TitleResponse,
  TitleSearchResponse,
  TitleType,
  WatchProvider,
  WatchRegion,
  WatchlistEntryResponse,
  WatchlistMemberResponse,
  WatchlistResponse,
  WatchStatus,
} from '../types/api'
import { notifyTokenRefreshed } from './auth'

const BASE_URL = '/api'
const REFRESHED_TOKEN_HEADER = 'X-Refreshed-Token'

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
  // Streaming-service settings (#270)
  watchRegion: string | null
  watchProviderIds: number[] | null
}

async function apiFetch(url: string, token: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, ...init?.headers },
  })
  if (response.status === 401) throw new UnauthorizedError()
  const refreshedToken = response.headers.get(REFRESHED_TOKEN_HEADER)
  if (refreshedToken) notifyTokenRefreshed(refreshedToken)
  return response
}

// ── Auth ─────────────────────────────────────────────────────

export async function exchangeToken(provider: string, credential: string): Promise<string> {
  const response = await fetch(`${BASE_URL}/auth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, credential }),
  })
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { message?: string }
    const err = new Error(error.message || `Token exchange failed: ${response.status}`)
    ;(err as Error & { status: number }).status = response.status
    throw err
  }
  const data = (await response.json()) as { token: string }
  return data.token
}

export async function registerUser(
  email: string,
  displayName: string,
  password: string,
): Promise<string> {
  const response = await fetch(`${BASE_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, displayName, password }),
  })
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { message?: string }
    const err = new Error(error.message || `Registration failed: ${response.status}`)
    ;(err as Error & { status: number }).status = response.status
    throw err
  }
  const data = (await response.json()) as { token: string }
  return data.token
}

// Standalone because AuthContext calls it while bootstrapping the session,
// before an ApiClient exists; AuthContext handles its own failures.
export async function getCurrentUser(token: string): Promise<BackendUser> {
  const response = await apiFetch(`${BASE_URL}/users/me`, token)
  if (!response.ok) throw new Error(`Failed to fetch current user: ${response.status}`)
  return response.json() as Promise<BackendUser>
}

// ── Authenticated API client ─────────────────────────────────

export type ApiClient = ReturnType<typeof createApiClient>

/**
 * Created by AuthContext with the current token. Any 401 triggers
 * onUnauthorized (central sign-out) before the error propagates, so
 * call sites only handle their own domain errors.
 */
export function createApiClient(token: string, onUnauthorized: () => void) {
  async function authedFetch(url: string, init?: RequestInit): Promise<Response> {
    try {
      return await apiFetch(url, token, init)
    } catch (e) {
      if (e instanceof UnauthorizedError) onUnauthorized()
      throw e
    }
  }

  return {
    // ── Title search ─────────────────────────────────────────

    async searchTitles(query: string, type?: string): Promise<TitleSearchResponse[]> {
      const params = new URLSearchParams({ q: query })
      if (type) params.set('type', type)

      const response = await authedFetch(`${BASE_URL}/titles/search?${params}`)
      if (!response.ok) throw new Error(`Search failed with status ${response.status}`)
      return response.json() as Promise<TitleSearchResponse[]>
    },

    async findOrCreateTitle(
      title: Pick<TitleSearchResponse, 'externalId' | 'externalSource' | 'type'>,
    ): Promise<number> {
      const response = await authedFetch(`${BASE_URL}/titles/resolve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          externalId: title.externalId,
          externalSource: title.externalSource,
          type: title.type,
        }),
      })
      if (!response.ok) throw new Error(`Failed to resolve title: ${response.status}`)
      return ((await response.json()) as TitleResponse).id
    },

    async getTitleDetail(
      externalSource: string,
      externalId: string,
      type: TitleType,
    ): Promise<TitleDetailResponse> {
      const params = new URLSearchParams({ externalSource, externalId, type })
      const response = await authedFetch(`${BASE_URL}/titles/detail?${params}`)
      if (!response.ok) throw new Error(`Failed to fetch title detail: ${response.status}`)
      return response.json() as Promise<TitleDetailResponse>
    },

    // ── People (#305) ────────────────────────────────────────

    async getPerson(personId: number): Promise<PersonDetailResponse> {
      const response = await authedFetch(`${BASE_URL}/people/${personId}`)
      if (!response.ok) throw new Error(`Failed to fetch person: ${response.status}`)
      return response.json() as Promise<PersonDetailResponse>
    },

    // ── User settings & watch providers (#270) ───────────────

    async getMe(): Promise<BackendUser> {
      const response = await authedFetch(`${BASE_URL}/users/me`)
      if (!response.ok) throw new Error(`Failed to fetch current user: ${response.status}`)
      return response.json() as Promise<BackendUser>
    },

    async updateStreamingSettings(
      userId: number,
      watchRegion: string,
      watchProviderIds: number[],
    ): Promise<BackendUser> {
      const response = await authedFetch(`${BASE_URL}/users/${userId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ watchRegion, watchProviderIds }),
      })
      if (!response.ok) throw new Error(`Failed to update streaming settings: ${response.status}`)
      return response.json() as Promise<BackendUser>
    },

    async getWatchProviders(region: string): Promise<WatchProvider[]> {
      const response = await authedFetch(`${BASE_URL}/watch-providers?region=${encodeURIComponent(region)}`)
      if (!response.ok) throw new Error(`Failed to fetch watch providers: ${response.status}`)
      return response.json() as Promise<WatchProvider[]>
    },

    async getWatchRegions(): Promise<WatchRegion[]> {
      const response = await authedFetch(`${BASE_URL}/watch-providers/regions`)
      if (!response.ok) throw new Error(`Failed to fetch watch regions: ${response.status}`)
      return response.json() as Promise<WatchRegion[]>
    },

    // ── Watchlist CRUD ───────────────────────────────────────

    async getWatchlists(): Promise<WatchlistResponse[]> {
      const response = await authedFetch(`${BASE_URL}/watchlists`)
      if (!response.ok) throw new Error(`Failed to fetch watchlists: ${response.status}`)
      return response.json() as Promise<WatchlistResponse[]>
    },

    async createWatchlist(name: string): Promise<WatchlistResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
      if (!response.ok) throw new Error(`Failed to create watchlist: ${response.status}`)
      return response.json() as Promise<WatchlistResponse>
    },

    async updateWatchlist(watchlistId: number, name: string): Promise<WatchlistResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
      if (!response.ok) throw new Error(`Failed to update watchlist: ${response.status}`)
      return response.json() as Promise<WatchlistResponse>
    },

    async deleteWatchlist(watchlistId: number): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error(`Failed to delete watchlist: ${response.status}`)
    },

    async setDefaultWatchlist(watchlistId: number): Promise<WatchlistResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/default`, {
        method: 'PATCH',
      })
      if (!response.ok) throw new Error(`Failed to set default watchlist: ${response.status}`)
      return response.json() as Promise<WatchlistResponse>
    },

    // ── Watchlist members ────────────────────────────────────

    async addMember(watchlistId: number, email: string): Promise<WatchlistMemberResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/members`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      })
      if (!response.ok) throw new Error(`Failed to add member: ${response.status}`)
      return response.json() as Promise<WatchlistMemberResponse>
    },

    async removeMember(watchlistId: number, userId: number): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/members/${userId}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error(`Failed to remove member: ${response.status}`)
    },

    async updateMemberRole(
      watchlistId: number,
      userId: number,
      role: MemberRole,
    ): Promise<WatchlistMemberResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/members/${userId}/role`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role }),
      })
      if (!response.ok) throw new Error(`Failed to update member role: ${response.status}`)
      return response.json() as Promise<WatchlistMemberResponse>
    },

    // ── Watchlist entries ────────────────────────────────────

    async getWatchlistEntries(watchlistId: number): Promise<WatchlistEntryResponse[]> {
      const entries: WatchlistEntryResponse[] = []
      let page = 0
      let totalPages: number
      do {
        const response = await authedFetch(
          `${BASE_URL}/watchlists/${watchlistId}/entries?page=${page}&size=200`,
        )
        if (!response.ok) throw new Error(`Failed to fetch watchlist entries: ${response.status}`)
        const body = (await response.json()) as {
          content: WatchlistEntryResponse[]
          totalPages: number
        }
        entries.push(...body.content)
        totalPages = body.totalPages
        page += 1
      } while (page < totalPages)
      return entries
    },

    async addToWatchlist(
      watchlistId: number,
      titleId: number,
      status: WatchStatus,
    ): Promise<WatchlistEntryResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/entries`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ titleId, status }),
      })
      if (!response.ok) throw new Error(`Failed to add to watchlist: ${response.status}`)
      return response.json() as Promise<WatchlistEntryResponse>
    },

    async updateWatchlistEntry(
      watchlistId: number,
      entryId: number,
      status: string,
    ): Promise<WatchlistEntryResponse> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      })
      if (!response.ok) throw new Error(`Failed to update watchlist entry: ${response.status}`)
      return response.json() as Promise<WatchlistEntryResponse>
    },

    async removeFromWatchlist(watchlistId: number, entryId: number): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error(`Failed to remove from watchlist: ${response.status}`)
    },

    // ── Title ratings (#273) ─────────────────────────────────
    // User-scoped like dismissals: a rating follows the caller across lists

    async rateTitle(titleId: number, rating: TitleRating): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/titles/${titleId}/rating`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rating }),
      })
      if (!response.ok) throw new Error(`Failed to rate title: ${response.status}`)
    },

    async clearTitleRating(titleId: number): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/titles/${titleId}/rating`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error(`Failed to clear title rating: ${response.status}`)
    },

    // ── Returning this week (#321) ───────────────────────────

    async getReturningEpisodes(watchlistId: number, days = 7): Promise<ReturningEpisode[]> {
      const response = await authedFetch(`${BASE_URL}/watchlists/${watchlistId}/returning?days=${days}`)
      if (!response.ok) throw new Error(`Failed to fetch returning episodes: ${response.status}`)
      return response.json() as Promise<ReturningEpisode[]>
    },

    // ── Suggestions ──────────────────────────────────────────

    async getSuggestions(watchlistId: number): Promise<SuggestionShelf[]> {
      const response = await authedFetch(`${BASE_URL}/suggestions?watchlistId=${watchlistId}`)
      if (!response.ok) throw new Error(`Failed to fetch suggestions: ${response.status}`)
      return response.json() as Promise<SuggestionShelf[]>
    },

    // "Not interested" (#268): user-scoped, permanent until undone — no watchlist id
    async dismissSuggestion(tmdbId: string): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/suggestions/dismissals`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tmdbId }),
      })
      if (!response.ok) throw new Error(`Failed to dismiss suggestion: ${response.status}`)
    },

    async undoDismissSuggestion(tmdbId: string): Promise<void> {
      const response = await authedFetch(`${BASE_URL}/suggestions/dismissals/${tmdbId}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error(`Failed to undo dismissal: ${response.status}`)
    },

    // ── Seasons & episode progress ──────────────────────────

    async getSeasons(titleId: number): Promise<SeasonSummary[]> {
      const response = await authedFetch(`${BASE_URL}/titles/${titleId}/seasons`)
      if (!response.ok) throw new Error(`Failed to fetch seasons: ${response.status}`)
      return response.json() as Promise<SeasonSummary[]>
    },

    async getSeasonDetail(titleId: number, seasonNumber: number): Promise<SeasonDetail> {
      const response = await authedFetch(`${BASE_URL}/titles/${titleId}/seasons/${seasonNumber}`)
      if (!response.ok) throw new Error(`Failed to fetch season detail: ${response.status}`)
      return response.json() as Promise<SeasonDetail>
    },

    async getEpisodeProgress(
      watchlistId: number,
      entryId: number,
      season?: number,
    ): Promise<EpisodeProgress[]> {
      const params = season != null ? `?season=${season}` : ''
      const response = await authedFetch(
        `${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}/episodes${params}`,
      )
      if (!response.ok) throw new Error(`Failed to fetch episode progress: ${response.status}`)
      return response.json() as Promise<EpisodeProgress[]>
    },

    async toggleEpisode(
      watchlistId: number,
      entryId: number,
      seasonNumber: number,
      episodeNumber: number,
    ): Promise<EpisodeProgress> {
      const response = await authedFetch(
        `${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}/episodes/${seasonNumber}/${episodeNumber}`,
        { method: 'PATCH' },
      )
      if (!response.ok) throw new Error(`Failed to toggle episode: ${response.status}`)
      return response.json() as Promise<EpisodeProgress>
    },

    async bulkMarkSeason(
      watchlistId: number,
      entryId: number,
      seasonNumber: number,
      watched: boolean,
      episodeNumbers: number[],
    ): Promise<void> {
      const response = await authedFetch(
        `${BASE_URL}/watchlists/${watchlistId}/entries/${entryId}/episodes/${seasonNumber}`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ watched, episodeNumbers }),
        },
      )
      if (!response.ok) throw new Error(`Failed to bulk mark season: ${response.status}`)
    },
  }
}
