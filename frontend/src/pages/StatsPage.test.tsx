import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import StatsPage from './StatsPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { Stats, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi,
// real WatchlistProvider on top.
const mockApi = {
  getWatchlists: vi.fn(),
  getStats: vi.fn(),
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
  members: [{ userId: 1, email: 'user@example.com', displayName: 'Test User', role: 'OWNER', joinedAt: '2026-01-01T00:00:00Z' }],
  isDefault: true,
}

const stats: Stats = {
  moviesFinished: 12,
  showsFinished: 3,
  episodesFinished: 248,
  totalMinutes: 14_820,
  movieMinutes: 1_440,
  episodeMinutes: 13_380,
  itemsMissingRuntime: 0,
  genres: [
    { genreId: 18, name: 'Drama', minutes: 9_100, titleCount: 7 },
    { genreId: 35, name: 'Comedy', minutes: 4_200, titleCount: 5 },
  ],
}

function renderStats() {
  render(
    <MemoryRouter>
      <WatchlistProvider>
        <StatsPage />
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getStats.mockResolvedValue(stats)
})

describe('StatsPage (#323)', () => {
  it('renders the totals as stat tiles', async () => {
    renderStats()

    // 14,820 minutes is 247 hours — hours, not days: "247h" is the brag.
    expect(await screen.findByText('247h')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('248')).toBeInTheDocument()
  })

  it('ranks the genre bars by watch time, largest first', async () => {
    renderStats()

    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByText('Drama')).toBeInTheDocument()
    expect(within(rows[0]).getByText('151h 40m')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Comedy')).toBeInTheDocument()
    expect(within(rows[1]).getByText('70h')).toBeInTheDocument()
  })

  it('scales each bar against the top genre, not against the total', async () => {
    // A title counts in every genre it carries, so the minutes over-sum — a
    // percentage-of-total bar would render >100% widths.
    renderStats()

    const rows = await screen.findAllByRole('listitem')
    expect(rows[0].querySelector('.genre-bar')).toHaveStyle({ width: '100%' })
    // 4,200 / 9,100 ≈ 46.2%
    const comedyWidth = rows[1].querySelector('.genre-bar')?.getAttribute('style')
    expect(comedyWidth).toContain('46.15')
  })

  it('says so when the genre bars over-sum', async () => {
    renderStats()

    expect(
      await screen.findByText(/counts in each of its genres/i),
    ).toBeInTheDocument()
  })

  it('flags watched items with no runtime, so the total reads as a floor', async () => {
    mockApi.getStats.mockResolvedValue({ ...stats, itemsMissingRuntime: 4 })
    renderStats()

    expect(await screen.findByText(/4 watched items have/i)).toBeInTheDocument()
    expect(screen.getByText(/the real total is a little higher/i)).toBeInTheDocument()
  })

  it('stays quiet about missing runtime when nothing is missing', async () => {
    renderStats()

    await screen.findByText('247h')
    expect(screen.queryByText(/no runtime on record/i)).not.toBeInTheDocument()
  })

  it('renders an empty watchlist without crashing', async () => {
    mockApi.getStats.mockResolvedValue({
      moviesFinished: 0,
      showsFinished: 0,
      episodesFinished: 0,
      totalMinutes: 0,
      movieMinutes: 0,
      episodeMinutes: 0,
      itemsMissingRuntime: 0,
      genres: [],
    } satisfies Stats)
    renderStats()

    // A genuine zero is an answer, not missing data.
    expect(await screen.findByText('0m')).toBeInTheDocument()
    expect(
      screen.getByText(/finish something and its genres will show up here/i),
    ).toBeInTheDocument()
  })

  it('shows an error line rather than a blank page when the fetch fails', async () => {
    mockApi.getStats.mockRejectedValue(new Error('boom'))
    renderStats()

    expect(await screen.findByText('Failed to load stats.')).toBeInTheDocument()
  })
})
