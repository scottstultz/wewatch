import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import DiscoverPage from './DiscoverPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { SuggestionShelf, TitleSearchResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): the page gets a fake ApiClient
// through useApi; the real WatchlistProvider runs on top of the same fake.
const mockApi = {
  getMe: vi.fn(),
  getWatchProviders: vi.fn(),
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  getSuggestions: vi.fn(),
  dismissSuggestion: vi.fn(),
  undoDismissSuggestion: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
}))

const watchlist: WatchlistResponse = {
  id: 1,
  name: 'My List',
  type: 'PERSONAL',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  members: [],
  isDefault: true,
}

function makeTitle(externalId: string, name: string): TitleSearchResponse {
  return {
    externalId,
    externalSource: 'tmdb',
    type: 'TV',
    name,
    overview: null,
    releaseDate: null,
    posterUrl: null,
  }
}

const severance = makeTitle('101', 'Severance')
const dark = makeTitle('102', 'Dark')

const shelf: SuggestionShelf = {
  reason: 'Because you like Sci-Fi',
  titles: [severance, dark],
  kind: 'GENRE_PROFILE',
  providerFiltered: false,
}

function renderPage() {
  return render(
    <MemoryRouter>
      <WatchlistProvider>
        <DiscoverPage />
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

async function renderWithShelves() {
  renderPage()
  await screen.findByText('Severance')
}

function dismiss(name: string) {
  fireEvent.click(screen.getByRole('button', { name: `Not interested in ${name}` }))
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getMe.mockResolvedValue({
    id: 1,
    email: 'user@example.com',
    displayName: 'Test User',
    watchRegion: null,
    watchProviderIds: null,
  })
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([])
  mockApi.getSuggestions.mockResolvedValue([shelf])
  mockApi.dismissSuggestion.mockResolvedValue(undefined)
  mockApi.undoDismissSuggestion.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('DiscoverPage suggestion dismissal (#268)', () => {
  it('removes the tile optimistically and records the dismissal', async () => {
    await renderWithShelves()

    dismiss('Severance')

    // Gone immediately, before the API call settles
    expect(screen.queryByText('Severance')).not.toBeInTheDocument()
    expect(screen.getByText('Dark')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/won’t suggest “Severance” again/)
    await waitFor(() => expect(mockApi.dismissSuggestion).toHaveBeenCalledWith('101'))
  })

  it('restores the tile and reverses the dismissal on Undo', async () => {
    await renderWithShelves()
    dismiss('Severance')

    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))

    expect(screen.getByText('Severance')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    await waitFor(() => expect(mockApi.undoDismissSuggestion).toHaveBeenCalledWith('101'))
  })

  it('reverts the removal when the dismissal API call fails', async () => {
    mockApi.dismissSuggestion.mockRejectedValue(new Error('network down'))
    await renderWithShelves()

    dismiss('Severance')
    expect(screen.queryByText('Severance')).not.toBeInTheDocument()

    // The dismissal never landed — the tile and snackbar roll back
    await waitFor(() => expect(screen.getByText('Severance')).toBeInTheDocument())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('hides the tile again when the undo API call fails', async () => {
    mockApi.undoDismissSuggestion.mockRejectedValue(new Error('network down'))
    await renderWithShelves()
    dismiss('Severance')

    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.getByText('Severance')).toBeInTheDocument()

    // The dismissal still stands server-side
    await waitFor(() => expect(screen.queryByText('Severance')).not.toBeInTheDocument())
  })

  it('hides the undo snackbar after 6 seconds', async () => {
    vi.useFakeTimers()
    renderPage()
    // Flush the provider + suggestions promise chains without real timers
    await act(async () => {})
    await act(async () => {})
    expect(screen.getByText('Severance')).toBeInTheDocument()

    dismiss('Severance')
    expect(screen.getByRole('status')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(6000)
    })

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByText('Severance')).not.toBeInTheDocument()
    expect(mockApi.dismissSuggestion).toHaveBeenCalledWith('101')
  })
})
