import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ShowDetailPage from './ShowDetailPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type {
  TitleDetailResponse,
  TitleSearchResponse,
  WatchProvider,
  WatchlistEntryResponse,
  WatchlistResponse,
} from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi, fake
// signed-in user via useAuth (this page reads it for canEdit), real
// WatchlistProvider on top of the same fake.
const mockApi = {
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  getTitleDetail: vi.fn(),
  getSeasons: vi.fn(),
  getSeasonDetail: vi.fn(),
  getEpisodeProgress: vi.fn(),
  getRecommendations: vi.fn(),
  findOrCreateTitle: vi.fn(),
  addToWatchlist: vi.fn(),
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

const entry: WatchlistEntryResponse = {
  id: 10,
  watchlistId: 1,
  addedByUserId: 1,
  titleId: 7,
  externalId: '1399',
  externalSource: 'tmdb',
  name: 'Game of Thrones',
  type: 'TV',
  posterUrl: null,
  status: 'WATCHING',
  addedAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  startedAt: null,
  completedAt: null,
  episodeProgress: null,
  myRating: null,
  genreIds: [],
  providerIds: [],
}

// The overview is load-bearing: without it (and with no cast) More Like This
// becomes the panel's lone section, which renders as a plain heading and fires
// the fetch trigger immediately — laziness would be untestable.
const detail: TitleDetailResponse = {
  externalId: '1399',
  externalSource: 'tmdb',
  type: 'TV',
  name: 'Game of Thrones',
  overview: 'Seven noble families fight for control of Westeros.',
  releaseDate: '2011-04-17',
  posterUrl: null,
  status: 'Ended',
  genres: ['Drama'],
  voteAverage: 8.4,
  voteCount: 20000,
  runtimeMinutes: null,
  seasonCount: 8,
  seasons: null,
  watchRegion: 'US',
  watchProviders: [],
  titleId: 7,
  myRating: null,
  cast: [],
  trailerUrl: null,
}

function makeRec(externalId: string, name: string): TitleSearchResponse {
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

const houseOfDragon = makeRec('94997', 'House of the Dragon')
const rings = makeRec('84773', 'The Rings of Power')

// One provider with a logo and one without, so the conditional <img> is
// exercised in both directions (#390).
const providers: WatchProvider[] = [
  { id: 384, name: 'Max', logoUrl: 'https://image.tmdb.org/t/p/original/max.jpg', displayPriority: 0 },
  { id: 15, name: 'Hulu', logoUrl: null, displayPriority: 1 },
]

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/library/10?wl=1']}>
      <WatchlistProvider>
        <Routes>
          <Route path="/library/:entryId" element={<ShowDetailPage />} />
        </Routes>
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([entry])
  mockApi.getTitleDetail.mockResolvedValue(detail)
  mockApi.getSeasons.mockResolvedValue([])
  mockApi.getSeasonDetail.mockResolvedValue({ seasonNumber: 1, name: 'Season 1', overview: null, posterUrl: null, episodes: [] })
  mockApi.getEpisodeProgress.mockResolvedValue([])
  mockApi.getRecommendations.mockResolvedValue([])
})

describe('ShowDetailPage "Where to watch" (#390)', () => {
  it('renders the providers and the JustWatch attribution for a streaming show', async () => {
    // Spread the shared fixture so the load-bearing overview survives (see above).
    mockApi.getTitleDetail.mockResolvedValue({ ...detail, watchProviders: providers })
    const { container } = renderPage()

    expect(await screen.findByRole('heading', { name: 'Where to watch' })).toBeInTheDocument()
    expect(screen.getByText('Max')).toBeInTheDocument()
    expect(screen.getByText('Hulu')).toBeInTheDocument()
    // Only the provider that has one renders a logo.
    expect(container.querySelectorAll('.provider-badge-logo')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'JustWatch' })).toHaveAttribute(
      'href',
      'https://www.justwatch.com',
    )
    // The panel rides the detail call the page already makes (#390).
    expect(mockApi.getTitleDetail).toHaveBeenCalledTimes(1)
  })

  it('omits the panel entirely when the show has no flatrate providers', async () => {
    // The shared fixture already carries watchProviders: [].
    renderPage()
    await screen.findByRole('heading', { name: 'Game of Thrones' })

    expect(screen.queryByRole('heading', { name: 'Where to watch' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'JustWatch' })).not.toBeInTheDocument()
  })
})

describe('ShowDetailPage "More Like This" (#363)', () => {
  it('does not fetch recommendations until the tab is opened, then renders them', async () => {
    mockApi.getRecommendations.mockResolvedValue([houseOfDragon, rings])
    renderPage()

    // The show lands but the recommendations are not fetched yet — lazy.
    await screen.findByRole('heading', { name: 'Game of Thrones' })
    expect(mockApi.getRecommendations).not.toHaveBeenCalled()
    expect(screen.queryByText('House of the Dragon')).not.toBeInTheDocument()

    fireEvent.click(await screen.findByRole('tab', { name: 'More Like This' }))

    expect(await screen.findByText('House of the Dragon')).toBeInTheDocument()
    expect(screen.getByText('The Rings of Power')).toBeInTheDocument()
    expect(mockApi.getRecommendations).toHaveBeenCalledWith('TV', '1399')
  })

  it('shows an empty-state message when there are no recommendations', async () => {
    mockApi.getRecommendations.mockResolvedValue([])
    renderPage()
    await screen.findByRole('heading', { name: 'Game of Thrones' })

    fireEvent.click(await screen.findByRole('tab', { name: 'More Like This' }))

    expect(await screen.findByText('No similar titles to show.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add to watchlist' })).not.toBeInTheDocument()
  })

  it('resolves then adds a recommendation, and the add survives reconcile', async () => {
    mockApi.getRecommendations.mockResolvedValue([houseOfDragon])
    mockApi.findOrCreateTitle.mockResolvedValue(42)
    mockApi.addToWatchlist.mockResolvedValue({ id: 9, status: 'WANT_TO_WATCH' })
    renderPage()
    await screen.findByRole('heading', { name: 'Game of Thrones' })

    fireEvent.click(await screen.findByRole('tab', { name: 'More Like This' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Add to watchlist' }))

    await waitFor(() => expect(mockApi.findOrCreateTitle).toHaveBeenCalledWith(houseOfDragon))
    expect(mockApi.addToWatchlist).toHaveBeenCalledWith(1, 42, 'WANT_TO_WATCH')
    await screen.findByRole('button', { name: /^Status: Want to Watch/ })
  })
})
