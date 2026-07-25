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

function makeCredit(
  externalId: string,
  name: string,
  type: 'MOVIE' | 'TV',
  character?: string | null,
  releaseDate: string | null = null,
): TitleSearchResponse {
  return {
    externalId,
    externalSource: 'tmdb',
    type,
    name,
    overview: null,
    releaseDate,
    posterUrl: null,
    character,
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

describe('PersonPage credited role (#401)', () => {
  it('renders the credited role on the tile', async () => {
    mockApi.getPerson.mockResolvedValue({
      ...keanu,
      credits: [makeCredit('603', 'The Matrix', 'MOVIE', 'Thomas A. Anderson')],
    })
    await renderLoaded()

    expect(screen.getByText('Thomas A. Anderson')).toBeInTheDocument()
    // The role rides the call the page already makes — no second fetch for it
    expect(mockApi.getPerson).toHaveBeenCalledTimes(1)
  })

  // ~3% of the credits surviving the backend's filters have no character; TMDB
  // simply hasn't detailed that film's cast, so it is normal rather than an error
  it('renders no role line for a credit with a blank or missing character', async () => {
    mockApi.getPerson.mockResolvedValue({
      ...keanu,
      credits: [
        makeCredit('603', 'The Matrix', 'MOVIE', null),
        makeCredit('1038', 'Constantine', 'MOVIE'),
      ],
    })
    await renderLoaded()

    expect(screen.getByText('Constantine')).toBeInTheDocument()
    expect(document.querySelectorAll('.title-role')).toHaveLength(0)
  })

  // The one-line clamp is CSS, which jsdom cannot see — what is assertable here
  // is that the full text stays reachable on hover
  it('exposes the full role on hover when it is too long for the tile', async () => {
    const role = 'Dr. Harding Fletcher - Marriage Counselor'
    mockApi.getPerson.mockResolvedValue({
      ...keanu,
      credits: [makeCredit('603', 'The Matrix', 'MOVIE', role)],
    })
    await renderLoaded()

    expect(screen.getByText(role)).toHaveAttribute('title', role)
  })

  // TitleCard's four other consumers send no character at all
  it('leaves tiles untouched when the response carries no role', async () => {
    await renderLoaded()

    expect(screen.getByText('The Matrix')).toBeInTheDocument()
    expect(document.querySelectorAll('.title-role')).toHaveLength(0)
  })
})

describe('PersonPage filmography order (#402)', () => {
  // Delivered in the mapper's popularity order, with the dates deliberately out
  // of order and one announced-but-undated project (TMDB really does ship those).
  //
  // ⚠️ The pre-1970 credit is load-bearing. Treating a null date as epoch 0 —
  // the obvious `(bt ?? 0) - (at ?? 0)` — still sorts it last against an all
  // post-1970 filmography, so every assertion here passes for the wrong reason
  // without it. Against a career that reaches back past 1970 the null lands
  // mid-grid instead, which is the bug the null-placement test exists to catch.
  const dated = [
    makeCredit('603', 'The Matrix', 'MOVIE', null, '1999-03-30'),
    makeCredit('603692', 'John Wick 4', 'MOVIE', null, '2023-03-22'),
    makeCredit('1038', 'Constantine 2', 'MOVIE', null, null),
    makeCredit('69078', 'Swedish Dicks', 'TV', null, '2016-11-01'),
    makeCredit('114472', 'The Continental', 'TV', null, '2023-09-22'),
    makeCredit('1648', 'Bill & Ted', 'MOVIE', null, '1989-02-17'),
    makeCredit('935', 'Dr. Strangelove', 'MOVIE', null, '1964-01-29'),
  ]

  function gridOrder(): string[] {
    return [...document.querySelectorAll('.title-name')].map(el => el.textContent ?? '')
  }

  async function renderCredits(credits: TitleSearchResponse[]) {
    mockApi.getPerson.mockResolvedValue({ ...keanu, credits })
    renderPage()
    await screen.findByText(credits[0].name)
  }

  it('leads with the popularity order the mapper delivered', async () => {
    await renderCredits(dated)

    expect(gridOrder()).toEqual([
      'The Matrix', 'John Wick 4', 'Constantine 2', 'Swedish Dicks', 'The Continental',
      'Bill & Ted', 'Dr. Strangelove',
    ])
    expect(screen.getByRole('button', { name: 'Popularity' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Newest' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('orders the filmography newest-first', async () => {
    await renderCredits(dated)

    fireEvent.click(screen.getByRole('button', { name: 'Newest' }))

    expect(gridOrder()).toEqual([
      'The Continental', 'John Wick 4', 'Swedish Dicks', 'The Matrix', 'Bill & Ted',
      'Dr. Strangelove', 'Constantine 2',
    ])
  })

  // An undated credit sorted as epoch 0 would read as the person's oldest work
  it('sorts an undated credit last rather than dropping it', async () => {
    await renderCredits(dated)

    fireEvent.click(screen.getByRole('button', { name: 'Newest' }))

    const order = gridOrder()
    expect(order[order.length - 1]).toBe('Constantine 2')
    expect(order).toHaveLength(dated.length)
  })

  it('composes with the All/Movies/TV filter, each preserving the other', async () => {
    await renderCredits(dated)

    fireEvent.click(screen.getByRole('button', { name: 'Newest' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Movies' }))

    // The order survives the filter change
    expect(gridOrder()).toEqual([
      'John Wick 4', 'The Matrix', 'Bill & Ted', 'Dr. Strangelove', 'Constantine 2',
    ])
    expect(screen.getByRole('button', { name: 'Newest' })).toHaveAttribute('aria-pressed', 'true')

    // ...and the filter survives the order change
    fireEvent.click(screen.getByRole('button', { name: 'Popularity' }))
    expect(gridOrder()).toEqual([
      'The Matrix', 'John Wick 4', 'Constantine 2', 'Bill & Ted', 'Dr. Strangelove',
    ])
    expect(screen.getByRole('tab', { name: 'Movies' })).toHaveAttribute('aria-selected', 'true')
  })

  // The comparator returns 0 for equal dates, so the stable sort leaves the
  // mapper's popularity order underneath as the tiebreak
  it('holds credits sharing a release date in their popularity order', async () => {
    await renderCredits([
      makeCredit('1', 'More Popular', 'MOVIE', null, '2020-01-01'),
      makeCredit('2', 'Less Popular', 'MOVIE', null, '2020-01-01'),
    ])

    fireEvent.click(screen.getByRole('button', { name: 'Newest' }))

    expect(gridOrder()).toEqual(['More Popular', 'Less Popular'])
  })

  // The mapper's 100-credit cap is applied before this sort and never re-applied
  it('shows the same credits in both orders', async () => {
    await renderCredits(dated)

    const byPopularity = gridOrder()
    fireEvent.click(screen.getByRole('button', { name: 'Newest' }))
    const byNewest = gridOrder()

    expect([...byNewest].sort()).toEqual([...byPopularity].sort())
  })
})
