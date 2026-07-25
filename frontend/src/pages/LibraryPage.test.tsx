import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import LibraryPage from './LibraryPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { GenreCatalog, TitleRating, WatchlistEntryResponse, WatchlistResponse } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi,
// fake signed-in user via useAuth, real WatchlistProvider on top.
const mockApi = {
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  rateTitle: vi.fn(),
  clearTitleRating: vi.fn(),
  getGenres: vi.fn(),
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

function makeEntry(
  myRating: TitleRating | null,
  overrides: Partial<WatchlistEntryResponse> = {},
): WatchlistEntryResponse {
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
    ...overrides,
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
  mockApi.getGenres.mockResolvedValue({ movie: [], tv: [] })
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

// ── Genre filter (#382) ─────────────────────────────────────

// "Action & Adventure" (TV 10759) is in the catalog but on no entry, so it is the guard
// that the panel offers only genres actually present in the list.
const CATALOG: GenreCatalog = {
  movie: [
    { id: 28, name: 'Action' },
    { id: 35, name: 'Comedy' },
    { id: 18, name: 'Drama' },
    { id: 10749, name: 'Romance' },
    { id: 878, name: 'Science Fiction' },
  ],
  tv: [{ id: 10759, name: 'Action & Adventure' }],
}

const GENRE_ENTRIES: WatchlistEntryResponse[] = [
  makeEntry(null, { id: 10, titleId: 55, name: 'The Matrix', genreIds: [28, 878] }),
  makeEntry(null, { id: 11, titleId: 56, name: 'Notting Hill', genreIds: [10749, 35] }),
  makeEntry(null, { id: 12, titleId: 57, name: 'Crazy Rich Asians', genreIds: [10749, 35, 18] }),
  // No genre data at all — nothing cached yet, or a genuinely empty genre_ids.
  makeEntry(null, { id: 13, titleId: 58, name: 'Top Gear', type: 'TV', genreIds: [] }),
  makeEntry(null, { id: 14, titleId: 59, name: 'Barbie', status: 'WANT_TO_WATCH', genreIds: [35, 10749] }),
  makeEntry(null, { id: 15, titleId: 60, name: 'Dune', status: 'WANT_TO_WATCH', genreIds: [878] }),
]

async function renderGenreLibrary(url = '/library') {
  mockApi.getWatchlistEntries.mockResolvedValue(GENRE_ENTRIES)
  mockApi.getGenres.mockResolvedValue(CATALOG)
  render(
    <MemoryRouter initialEntries={[url]}>
      <WatchlistProvider>
        <LibraryPage />
      </WatchlistProvider>
    </MemoryRouter>,
  )
  // The Genres trigger only renders once entries *and* the catalog have landed.
  await screen.findByRole('button', { name: /^Genres/ })
}

const genresTrigger = () => screen.getByRole('button', { name: /^Genres/ })

describe('LibraryPage genre filter (#382)', () => {
  it('shows only titles carrying every selected genre (AND, not OR)', async () => {
    await renderGenreLibrary('/library?genres=35,18')

    // Comedy AND Drama
    expect(screen.getByText('Crazy Rich Asians')).toBeInTheDocument()
    // Comedy but not Drama — an OR filter would have kept it
    expect(screen.queryByText('Notting Hill')).not.toBeInTheDocument()
    expect(screen.queryByText('The Matrix')).not.toBeInTheDocument()
  })

  it('excludes entries with no genre data while a filter is active', async () => {
    await renderGenreLibrary()
    expect(screen.getByText('Top Gear')).toBeInTheDocument()

    fireEvent.click(genresTrigger())
    fireEvent.click(screen.getByRole('checkbox', { name: 'Comedy' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(screen.queryByText('Top Gear')).not.toBeInTheDocument())
    expect(screen.getByText('Notting Hill')).toBeInTheDocument()
  })

  it('composes with the status tab and the title search', async () => {
    await renderGenreLibrary('/library?genres=35')

    // Watching + Comedy. Barbie is Comedy but WANT_TO_WATCH.
    expect(screen.getByText('Notting Hill')).toBeInTheDocument()
    expect(screen.getByText('Crazy Rich Asians')).toBeInTheDocument()
    expect(screen.queryByText('Barbie')).not.toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Search your library…'), {
      target: { value: 'notting' },
    })

    expect(screen.getByText('Notting Hill')).toBeInTheDocument()
    expect(screen.queryByText('Crazy Rich Asians')).not.toBeInTheDocument()
  })

  // Regression guard for the setSearchParams merge: the old
  // setSearchParams({ status }) replaced the whole query string and dropped ?genres=.
  it('keeps the genre filter when switching status tabs', async () => {
    await renderGenreLibrary('/library?genres=35')

    fireEvent.click(screen.getByRole('button', { name: 'Want to Watch' }))

    // Comedy survived the tab switch: Barbie carries it, Dune does not.
    await screen.findByText('Barbie')
    expect(screen.queryByText('Dune')).not.toBeInTheDocument()
    expect(screen.getByLabelText('1 selected')).toBeInTheDocument()
  })

  it('round-trips the selection through the URL and badges the count', async () => {
    await renderGenreLibrary('/library?genres=35,18')

    expect(screen.getByLabelText('2 selected')).toHaveTextContent('2')

    fireEvent.click(genresTrigger())
    expect(screen.getByRole('checkbox', { name: 'Comedy' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Drama' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Romance' })).not.toBeChecked()
  })

  it('offers only genres present in the loaded entries', async () => {
    await renderGenreLibrary()

    fireEvent.click(genresTrigger())

    expect(screen.getByRole('checkbox', { name: 'Action' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Science Fiction' })).toBeInTheDocument()
    // In the catalog, but on no entry in this list
    expect(screen.queryByRole('checkbox', { name: 'Action & Adventure' })).not.toBeInTheDocument()
  })

  it('ignores a selected genre that is absent from the list rather than filtering by it', async () => {
    // 10759 is a real catalog id but on no entry here — it must not silently empty the grid.
    await renderGenreLibrary('/library?genres=10759')

    expect(screen.getByText('The Matrix')).toBeInTheDocument()
    expect(screen.getByText('Top Gear')).toBeInTheDocument()
    expect(screen.queryByLabelText(/selected$/)).not.toBeInTheDocument()
  })

  it('hides the trigger entirely when the catalog cannot be loaded', async () => {
    mockApi.getWatchlistEntries.mockResolvedValue(GENRE_ENTRIES)
    mockApi.getGenres.mockRejectedValue(new Error('network down'))
    render(
      <MemoryRouter initialEntries={['/library']}>
        <WatchlistProvider>
          <LibraryPage />
        </WatchlistProvider>
      </MemoryRouter>,
    )

    // The page still renders its titles; only the filter is gone.
    await screen.findByText('The Matrix')
    expect(screen.queryByRole('button', { name: /^Genres/ })).not.toBeInTheDocument()
  })

  // Without its own branch the zero-match case falls through to "No titles in progress",
  // which blames the status tab for something only the genre filter can explain.
  it('explains an empty grid as a genre mismatch rather than an empty tab', async () => {
    // Science Fiction AND Romance — nothing on this list carries both.
    await renderGenreLibrary('/library?genres=878,10749')

    expect(screen.getByText('No titles here match those genres.')).toBeInTheDocument()
    expect(screen.queryByText(/No titles in progress/)).not.toBeInTheDocument()
  })

  it('drops the filter in one click via Clear', async () => {
    await renderGenreLibrary('/library?genres=35,18')
    expect(screen.queryByText('The Matrix')).not.toBeInTheDocument()

    fireEvent.click(genresTrigger())
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))

    await screen.findByText('The Matrix')
    expect(screen.queryByLabelText(/selected$/)).not.toBeInTheDocument()
  })
})
