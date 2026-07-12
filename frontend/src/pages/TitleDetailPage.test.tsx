import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TitleDetailPage from './TitleDetailPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { TitleDetailResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): the page gets a fake ApiClient
// through useApi; the real WatchlistProvider runs on top of the same fake.
const mockApi = {
  getTitleDetail: vi.fn(),
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
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

function makeDetail(overrides: Partial<TitleDetailResponse> = {}): TitleDetailResponse {
  return {
    externalId: '603',
    externalSource: 'tmdb',
    type: 'MOVIE',
    name: 'The Matrix',
    overview: 'A computer hacker learns the truth.',
    releaseDate: '1999-03-31',
    posterUrl: null,
    status: 'Released',
    genres: ['Action'],
    voteAverage: 8.2,
    voteCount: 25000,
    runtimeMinutes: 136,
    seasonCount: null,
    seasons: null,
    watchRegion: 'US',
    watchProviders: [],
    titleId: null,
    myRating: null,
    cast: [],
    ...overrides,
  }
}

function renderPage(path = '/title/movie/tmdb/603') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <WatchlistProvider>
        <Routes>
          <Route path="/title/:type/:source/:externalId" element={<TitleDetailPage />} />
        </Routes>
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getTitleDetail.mockResolvedValue(makeDetail())
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([])
})

describe('TitleDetailPage runtime (#311)', () => {
  it('shows a movie runtime formatted as hours and minutes', async () => {
    renderPage()

    expect(await screen.findByText('2h 16m')).toBeInTheDocument()
  })

  it('drops the minutes segment for a whole-hour runtime', async () => {
    mockApi.getTitleDetail.mockResolvedValue(makeDetail({ runtimeMinutes: 120 }))
    renderPage()

    expect(await screen.findByText('2h')).toBeInTheDocument()
  })

  it('omits the runtime when TMDB has none', async () => {
    mockApi.getTitleDetail.mockResolvedValue(makeDetail({ runtimeMinutes: null }))
    renderPage()

    // Wait for the title to paint, then confirm no runtime chip rendered
    expect(await screen.findByRole('heading', { name: 'The Matrix' })).toBeInTheDocument()
    expect(screen.queryByText(/\dh/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\dm$/)).not.toBeInTheDocument()
  })
})
