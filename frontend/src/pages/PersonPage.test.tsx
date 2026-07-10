import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import PersonPage from './PersonPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type {
  PersonDetailResponse,
  TitleSearchResponse,
  WatchlistEntryResponse,
  WatchlistResponse,
} from '../types/api'

// Mock at the service layer (#287 convention): the page gets a fake ApiClient
// through useApi; the real WatchlistProvider runs on top of the same fake.
const mockApi = {
  getPerson: vi.fn(),
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
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

function makeCredit(externalId: string, name: string, type: 'MOVIE' | 'TV'): TitleSearchResponse {
  return {
    externalId,
    externalSource: 'tmdb',
    type,
    name,
    overview: null,
    releaseDate: null,
    posterUrl: null,
  }
}

const matrix = makeCredit('603', 'The Matrix', 'MOVIE')
const constantine = makeCredit('1038', 'Constantine', 'MOVIE')
const swedishDicks = makeCredit('69078', 'Swedish Dicks', 'TV')

const keanu: PersonDetailResponse = {
  id: 1245,
  name: 'Keanu Reeves',
  biography: 'A Canadian actor.',
  profileUrl: 'https://img/keanu.jpg',
  knownForDepartment: 'Acting',
  birthday: '1964-09-02',
  placeOfBirth: 'Beirut, Lebanon',
  credits: [matrix, constantine, swedishDicks],
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/person/1245']}>
      <WatchlistProvider>
        <Routes>
          <Route path="/person/:personId" element={<PersonPage />} />
        </Routes>
      </WatchlistProvider>
    </MemoryRouter>,
  )
}

async function renderLoaded() {
  renderPage()
  await screen.findByText('The Matrix')
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.getPerson.mockResolvedValue(keanu)
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([])
})

describe('PersonPage (#305)', () => {
  it('renders the person header and the full filmography', async () => {
    await renderLoaded()

    expect(screen.getByRole('heading', { name: 'Keanu Reeves' })).toBeInTheDocument()
    expect(screen.getByText('Acting')).toBeInTheDocument()
    expect(screen.getByText('Born 1964')).toBeInTheDocument()
    expect(screen.getByText('A Canadian actor.')).toBeInTheDocument()
    expect(screen.getByText('Constantine')).toBeInTheDocument()
    expect(screen.getByText('Swedish Dicks')).toBeInTheDocument()
    await waitFor(() => expect(mockApi.getPerson).toHaveBeenCalledWith(1245))
  })

  it('renders the silhouette when TMDB has no headshot', async () => {
    mockApi.getPerson.mockResolvedValue({ ...keanu, profileUrl: null })
    await renderLoaded()

    expect(document.querySelector('img.show-detail-poster')).toBeNull()
    expect(document.querySelector('.cast-photo-placeholder')).toBeInTheDocument()
  })

  it('narrows the grid to movies or TV and back to all', async () => {
    await renderLoaded()

    fireEvent.click(screen.getByRole('tab', { name: 'Movies' }))
    expect(screen.getByText('The Matrix')).toBeInTheDocument()
    expect(screen.queryByText('Swedish Dicks')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'TV' }))
    expect(screen.getByText('Swedish Dicks')).toBeInTheDocument()
    expect(screen.queryByText('The Matrix')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'All' }))
    expect(screen.getByText('The Matrix')).toBeInTheDocument()
    expect(screen.getByText('Swedish Dicks')).toBeInTheDocument()
  })

  it('shows the status chip for credits already on the watchlist', async () => {
    const entry = {
      id: 7,
      externalId: '603',
      externalSource: 'tmdb',
      status: 'WATCHED',
    } as WatchlistEntryResponse
    mockApi.getWatchlistEntries.mockResolvedValue([entry])
    await renderLoaded()

    // The Matrix carries the chip; the other two still offer the + button
    await screen.findByRole('button', { name: /^Status: Watched/ })
    expect(screen.getAllByRole('button', { name: 'Add to watchlist' })).toHaveLength(2)
  })

  it('resolves the title then adds it when the + button is clicked', async () => {
    mockApi.findOrCreateTitle.mockResolvedValue(42)
    mockApi.addToWatchlist.mockResolvedValue({ id: 9, status: 'WANT_TO_WATCH' })
    await renderLoaded()

    fireEvent.click(screen.getAllByRole('button', { name: 'Add to watchlist' })[0])

    await waitFor(() => expect(mockApi.findOrCreateTitle).toHaveBeenCalledWith(matrix))
    expect(mockApi.addToWatchlist).toHaveBeenCalledWith(1, 42, 'WANT_TO_WATCH')
    await screen.findByRole('button', { name: /^Status: Want to Watch/ })
  })

  // A tile rendered before reconciliation could be added to the watchlist, and
  // the in-flight reconcile would then delete the optimistic status.
  it('holds the tiles back until the watchlist has been reconciled', async () => {
    let resolveEntries: (entries: WatchlistEntryResponse[]) => void = () => {}
    mockApi.getWatchlistEntries.mockReturnValue(
      new Promise<WatchlistEntryResponse[]>(resolve => { resolveEntries = resolve }),
    )
    renderPage()

    // The header paints as soon as the person loads; the grid waits
    expect(await screen.findByRole('heading', { name: 'Keanu Reeves' })).toBeInTheDocument()
    expect(screen.queryByText('The Matrix')).not.toBeInTheDocument()

    resolveEntries([])

    expect(await screen.findByText('The Matrix')).toBeInTheDocument()
  })

  it('surfaces an error when the person fails to load', async () => {
    mockApi.getPerson.mockRejectedValue(new Error('boom'))
    renderPage()

    expect(await screen.findByText('Failed to load person details.')).toBeInTheDocument()
  })
})
