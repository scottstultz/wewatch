import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import AppLayout from './AppLayout'
import { ReturningNotificationsProvider } from '../contexts/ReturningNotificationsContext'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import { storeSeenReturning } from '../services/returningSeenStorage'
import type { ApiClient } from '../services/api'
import type { ReturningEpisode, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287): fake ApiClient via useApi, real Watchlist +
// ReturningNotifications providers on top. AppLayout also reads useAuth for the user chrome.
const mockApi = {
  getWatchlists: vi.fn(),
  getReturningEpisodes: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
  useAuth: () => ({ user: { name: 'Test User' }, signOut: vi.fn() }),
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

const returning = (entryId: number, seasonNumber: number, episodeNumber: number): ReturningEpisode => ({
  entryId,
  externalId: String(entryId),
  externalSource: 'tmdb',
  showName: 'Show',
  posterUrl: null,
  seasonNumber,
  episodeNumber,
  episodeName: null,
  airDate: '2026-07-17',
  runtimeMinutes: 52,
})

function renderLayout() {
  render(
    <MemoryRouter>
      <WatchlistProvider>
        <ReturningNotificationsProvider>
          <AppLayout />
        </ReturningNotificationsProvider>
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getReturningEpisodes.mockResolvedValue([])
})

describe('AppLayout — Returning this week badge (#360)', () => {
  it('shows the unseen count on the Home nav entry (sidebar + mobile bar)', async () => {
    mockApi.getReturningEpisodes.mockResolvedValue([returning(10, 2, 4), returning(11, 1, 1)])

    renderLayout()

    // Rendered in both the sidebar and the mobile bottom bar; CSS hides one, jsdom keeps both.
    const badges = await screen.findAllByLabelText('2 returning this week')
    expect(badges).toHaveLength(2)
    badges.forEach(badge => expect(badge).toHaveTextContent('2'))
  })

  it('shows no badge when nothing is returning', async () => {
    mockApi.getReturningEpisodes.mockResolvedValue([])

    renderLayout()

    await waitFor(() => expect(mockApi.getReturningEpisodes).toHaveBeenCalled())
    expect(screen.queryByLabelText(/returning this week/i)).not.toBeInTheDocument()
  })

  it('shows no badge when every returning episode is already seen', async () => {
    storeSeenReturning(1, ['10:2:4', '11:1:1'])
    mockApi.getReturningEpisodes.mockResolvedValue([returning(10, 2, 4), returning(11, 1, 1)])

    renderLayout()

    await waitFor(() => expect(mockApi.getReturningEpisodes).toHaveBeenCalled())
    expect(screen.queryByLabelText(/returning this week/i)).not.toBeInTheDocument()
  })

  it('renders the nav without a badge when the returning fetch fails', async () => {
    mockApi.getReturningEpisodes.mockRejectedValue(new Error('boom'))

    renderLayout()

    await waitFor(() => expect(mockApi.getReturningEpisodes).toHaveBeenCalled())
    expect(screen.getAllByRole('link', { name: /home/i }).length).toBeGreaterThan(0)
    expect(screen.queryByLabelText(/returning this week/i)).not.toBeInTheDocument()
  })
})
