import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TitleDetailPage from './TitleDetailPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { TitleDetailResponse, TitleSearchResponse, WatchlistEntryResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): the page gets a fake ApiClient
// through useApi; the real WatchlistProvider runs on top of the same fake.
const mockApi = {
  getTitleDetail: vi.fn(),
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  getRecommendations: vi.fn(),
  findOrCreateTitle: vi.fn(),
  addToWatchlist: vi.fn(),
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
    trailerUrl: null,
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

function makeRec(externalId: string, name: string): TitleSearchResponse {
  return {
    externalId,
    externalSource: 'tmdb',
    type: 'MOVIE',
    name,
    overview: null,
    releaseDate: null,
    posterUrl: null,
  }
}

const reloaded = makeRec('604', 'The Matrix Reloaded')
const revolutions = makeRec('605', 'The Matrix Revolutions')

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getTitleDetail.mockResolvedValue(makeDetail())
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([])
  mockApi.getRecommendations.mockResolvedValue([])
})

describe('TitleDetailPage "More Like This" (#358)', () => {
  it('does not fetch recommendations until the tab is opened, then renders them', async () => {
    mockApi.getRecommendations.mockResolvedValue([reloaded, revolutions])
    renderPage()

    // The detail lands but the recommendations are not fetched yet — lazy.
    await screen.findByRole('heading', { name: 'The Matrix' })
    expect(mockApi.getRecommendations).not.toHaveBeenCalled()
    expect(screen.queryByText('The Matrix Reloaded')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))

    expect(await screen.findByText('The Matrix Reloaded')).toBeInTheDocument()
    expect(screen.getByText('The Matrix Revolutions')).toBeInTheDocument()
    expect(mockApi.getRecommendations).toHaveBeenCalledWith('MOVIE', '603')
  })

  it('shows an empty-state message when there are no recommendations', async () => {
    mockApi.getRecommendations.mockResolvedValue([])
    renderPage()
    await screen.findByRole('heading', { name: 'The Matrix' })

    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))

    expect(await screen.findByText('No similar titles to show.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add to watchlist' })).not.toBeInTheDocument()
  })

  it('resolves then adds a recommendation, and the add survives reconcile', async () => {
    mockApi.getRecommendations.mockResolvedValue([reloaded])
    mockApi.findOrCreateTitle.mockResolvedValue(42)
    mockApi.addToWatchlist.mockResolvedValue({ id: 9, status: 'WANT_TO_WATCH' })
    renderPage()
    await screen.findByRole('heading', { name: 'The Matrix' })

    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Add to watchlist' }))

    await waitFor(() => expect(mockApi.findOrCreateTitle).toHaveBeenCalledWith(reloaded))
    expect(mockApi.addToWatchlist).toHaveBeenCalledWith(1, 42, 'WANT_TO_WATCH')
    await screen.findByRole('button', { name: /^Status: Want to Watch/ })
  })

  it('holds the recommendation tiles back until the watchlist has reconciled (#305)', async () => {
    let resolveEntries: (entries: WatchlistEntryResponse[]) => void = () => {}
    mockApi.getWatchlistEntries.mockReturnValue(
      new Promise<WatchlistEntryResponse[]>(resolve => { resolveEntries = resolve }),
    )
    mockApi.getRecommendations.mockResolvedValue([reloaded])
    renderPage()

    // The hero paints and the tab is present, but its grid waits on the reconcile.
    await screen.findByRole('heading', { name: 'The Matrix' })
    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))
    expect(screen.queryByText('The Matrix Reloaded')).not.toBeInTheDocument()

    resolveEntries([])

    expect(await screen.findByText('The Matrix Reloaded')).toBeInTheDocument()
  })
})

describe('TitleDetailPage "More Like This" same-route navigation (#406)', () => {
  const reloadedDetail = makeDetail({
    externalId: '604',
    name: 'The Matrix Reloaded',
    overview: 'Neo and the rebels race against time.',
  })
  const anotherRec = makeRec('700', 'The Animatrix')

  it('resets the tab/scroll and refetches recommendations for the newly opened title', async () => {
    mockApi.getTitleDetail.mockImplementation((_source: string, externalId: string) =>
      Promise.resolve(externalId === '604' ? reloadedDetail : makeDetail()),
    )
    mockApi.getRecommendations.mockImplementation((_type: string, externalId: string) =>
      Promise.resolve(externalId === '603' ? [reloaded, revolutions] : [anotherRec]),
    )
    const scrollSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    renderPage()

    await screen.findByRole('heading', { name: 'The Matrix' })
    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))
    await screen.findByText('The Matrix Reloaded')
    scrollSpy.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'View details for The Matrix Reloaded' }))

    // Navigated to the clicked title's own detail page.
    await screen.findByRole('heading', { name: 'The Matrix Reloaded' })
    expect(mockApi.getTitleDetail).toHaveBeenLastCalledWith('tmdb', '604', 'MOVIE')
    expect(scrollSpy).toHaveBeenCalledWith(0, 0)

    // The previous title's recommendation grid is gone rather than left stale —
    // the panel resets to its default tab instead of staying on More Like This.
    expect(screen.queryByText('The Matrix Revolutions')).not.toBeInTheDocument()
    expect(mockApi.getRecommendations).toHaveBeenCalledTimes(1)

    // Reopening the tab fetches recommendations for the *new* title, not the
    // ones held over from the title navigated away from.
    fireEvent.click(screen.getByRole('tab', { name: 'More Like This' }))
    expect(await screen.findByText('The Animatrix')).toBeInTheDocument()
    expect(mockApi.getRecommendations).toHaveBeenLastCalledWith('MOVIE', '604')
  })
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

describe('TitleDetailPage trailer link (#340)', () => {
  it('links out to the trailer in a new tab', async () => {
    mockApi.getTitleDetail.mockResolvedValue(
      makeDetail({ trailerUrl: 'https://www.youtube.com/watch?v=m8e-FF8MsqU' }),
    )
    renderPage()

    const link = await screen.findByRole('link', { name: /watch trailer/i })
    expect(link).toHaveAttribute('href', 'https://www.youtube.com/watch?v=m8e-FF8MsqU')
    expect(link).toHaveAttribute('target', '_blank')
    // Without noopener the opened tab gets a handle back to this one
    expect(link.getAttribute('rel')).toContain('noopener')
  })

  it('renders no link when TMDB has no trailer', async () => {
    mockApi.getTitleDetail.mockResolvedValue(makeDetail({ trailerUrl: null }))
    renderPage()

    // Wait for the detail to land, then confirm there is no dead affordance
    expect(await screen.findByRole('heading', { name: 'The Matrix' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /watch trailer/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/watch trailer/i)).not.toBeInTheDocument()
  })
})
