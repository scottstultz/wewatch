import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import HomePage from './HomePage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { ReturningEpisode, WatchlistEntryResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi,
// real WatchlistProvider on top.
const mockApi = {
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  getReturningEpisodes: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
}))

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async importOriginal => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => mockNavigate,
}))

const watchlist: WatchlistResponse = {
  id: 1,
  name: 'My List',
  type: 'PERSONAL',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  members: [{ userId: 1, email: 'user@example.com', displayName: 'Test User', role: 'OWNER', joinedAt: '2026-01-01T00:00:00Z' }],
  isDefault: true,
}

const severanceEntry: WatchlistEntryResponse = {
  id: 10,
  watchlistId: 1,
  addedByUserId: 1,
  titleId: 55,
  externalId: '95396',
  externalSource: 'tmdb',
  name: 'Severance',
  type: 'TV',
  posterUrl: null,
  status: 'WATCHING',
  addedAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  startedAt: '2026-01-01T00:00:00Z',
  completedAt: null,
  episodeProgress: null,
  myRating: null,
}

const severanceReturning: ReturningEpisode = {
  entryId: 10,
  externalId: '95396',
  externalSource: 'tmdb',
  showName: 'Severance',
  posterUrl: null,
  seasonNumber: 2,
  episodeNumber: 4,
  episodeName: "Woe's Hollow",
  airDate: '2026-07-17',
  runtimeMinutes: 52,
}

function renderHome() {
  render(
    <MemoryRouter>
      <WatchlistProvider>
        <HomePage />
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([severanceEntry])
  mockApi.getReturningEpisodes.mockResolvedValue([])
})

describe('HomePage — Returning this week (#321)', () => {
  it('lists a returning show with its episode and air date', async () => {
    mockApi.getReturningEpisodes.mockResolvedValue([severanceReturning])

    renderHome()

    expect(await screen.findByText('Returning this week')).toBeInTheDocument()
    expect(screen.getByText('S2E4 · Jul 17, 2026')).toBeInTheDocument()
  })

  it('hides the panel entirely when nothing is airing soon', async () => {
    mockApi.getReturningEpisodes.mockResolvedValue([])

    renderHome()

    // Wait for the page to settle, then assert the panel never appeared — an empty
    // "Returning this week" box is noise, so there is no empty state to render.
    expect(await screen.findByText('Continue watching')).toBeInTheDocument()
    expect(screen.queryByText('Returning this week')).not.toBeInTheDocument()
  })

  it('still renders the page when the returning fetch fails', async () => {
    mockApi.getReturningEpisodes.mockRejectedValue(new Error('boom'))

    renderHome()

    expect(await screen.findByText('Continue watching')).toBeInTheDocument()
    expect(screen.queryByText('Returning this week')).not.toBeInTheDocument()
    expect(screen.queryByText('Failed to load watchlist data.')).not.toBeInTheDocument()
  })

  it('opens the show when a returning row is clicked', async () => {
    mockApi.getReturningEpisodes.mockResolvedValue([severanceReturning])

    renderHome()

    const row = await screen.findByText('S2E4 · Jul 17, 2026')
    fireEvent.click(row.closest('li')!)

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/library/10?wl=1')
    })
  })

  it('asks the backend for a seven-day window', async () => {
    renderHome()

    await waitFor(() => {
      expect(mockApi.getReturningEpisodes).toHaveBeenCalledWith(1)
    })
  })
})
