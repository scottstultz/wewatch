import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import LibraryPage from './LibraryPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { TitleRating, WatchlistEntryResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi,
// fake signed-in user via useAuth, real WatchlistProvider on top.
const mockApi = {
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  rateTitle: vi.fn(),
  clearTitleRating: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
  useAuth: () => ({
    token: 'test-token',
    user: { id: 1, name: 'Test User', email: 'user@example.com' },
    api: null,
    sessionExpired: false,
    handleCredential: vi.fn(),
    signOut: vi.fn(),
  }),
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

function makeEntry(myRating: TitleRating | null): WatchlistEntryResponse {
  return {
    id: 10,
    watchlistId: 1,
    addedByUserId: 1,
    titleId: 55,
    externalId: '603',
    externalSource: 'tmdb',
    name: 'The Matrix',
    type: 'MOVIE',
    posterUrl: null,
    status: 'WATCHING', // the Library's default tab
    addedAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    startedAt: null,
    completedAt: null,
    episodeProgress: null,
    myRating,
    genreIds: [],
  }
}

async function renderLibrary(entry: WatchlistEntryResponse) {
  mockApi.getWatchlistEntries.mockResolvedValue([entry])
  render(
    <MemoryRouter>
      <WatchlistProvider>
        <LibraryPage />
      </WatchlistProvider>
    </MemoryRouter>,
  )
  await screen.findByText('The Matrix')
}

const thumbsUp = () => screen.getByRole('button', { name: 'Thumbs up — more like this' })
const activeThumbsUp = () => screen.getByRole('button', { name: 'Remove thumbs up' })

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.rateTitle.mockResolvedValue(undefined)
  mockApi.clearTitleRating.mockResolvedValue(undefined)
})

describe('LibraryPage thumbs ratings (#273)', () => {
  it('flips the thumb optimistically and sends the rating', async () => {
    await renderLibrary(makeEntry(null))

    fireEvent.click(thumbsUp())

    // Pressed immediately, before the API call settles
    expect(activeThumbsUp()).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(mockApi.rateTitle).toHaveBeenCalledWith(55, 'UP'))
    expect(mockApi.clearTitleRating).not.toHaveBeenCalled()
  })

  it('reverts the thumb when the rating API call fails', async () => {
    mockApi.rateTitle.mockRejectedValue(new Error('network down'))
    await renderLibrary(makeEntry(null))

    fireEvent.click(thumbsUp())
    expect(activeThumbsUp()).toHaveAttribute('aria-pressed', 'true')

    await waitFor(() => expect(thumbsUp()).toHaveAttribute('aria-pressed', 'false'))
  })

  it('clears an active rating via DELETE when tapping the pressed thumb', async () => {
    await renderLibrary(makeEntry('UP'))

    fireEvent.click(activeThumbsUp())

    expect(thumbsUp()).toHaveAttribute('aria-pressed', 'false')
    await waitFor(() => expect(mockApi.clearTitleRating).toHaveBeenCalledWith(55))
    expect(mockApi.rateTitle).not.toHaveBeenCalled()
  })

  it('restores the rating when the clear API call fails', async () => {
    mockApi.clearTitleRating.mockRejectedValue(new Error('network down'))
    await renderLibrary(makeEntry('UP'))

    fireEvent.click(activeThumbsUp())
    expect(thumbsUp()).toHaveAttribute('aria-pressed', 'false')

    await waitFor(() => expect(activeThumbsUp()).toHaveAttribute('aria-pressed', 'true'))
  })
})
