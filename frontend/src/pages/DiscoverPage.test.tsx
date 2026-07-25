import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import DiscoverPage from './DiscoverPage'
import { WatchlistProvider } from '../contexts/WatchlistContext'
import type { ApiClient } from '../services/api'
import type { GenreCatalog, SuggestionShelf, TitleSearchResponse, WatchlistResponse } from '../types/api'

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
  searchTitles: vi.fn(),
  getGenres: vi.fn(),
  browseByGenre: vi.fn(),
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

function makeTitle(
  externalId: string,
  name: string,
  overrides: Partial<TitleSearchResponse> = {},
): TitleSearchResponse {
  return {
    externalId,
    externalSource: 'tmdb',
    type: 'TV',
    name,
    overview: null,
    releaseDate: null,
    posterUrl: null,
    ...overrides,
  }
}

// TMDB's real split: Comedy (35) is in both catalogs, Romance and Science Fiction
// are movie-only, and TV's nearest equivalent is a different genre with a
// different id (#384)
const genreCatalog: GenreCatalog = {
  movie: [
    { id: 35, name: 'Comedy' },
    { id: 10749, name: 'Romance' },
    { id: 878, name: 'Science Fiction' },
  ],
  tv: [
    { id: 35, name: 'Comedy' },
    { id: 10765, name: 'Sci-Fi & Fantasy' },
  ],
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
  mockApi.searchTitles.mockResolvedValue({ titles: [], people: [] })
  // Without these two the page's own effects throw synchronously and every test in
  // this file dies on "api.getGenres is not a function" (the #382 failure mode)
  mockApi.getGenres.mockResolvedValue(genreCatalog)
  mockApi.browseByGenre.mockResolvedValue([])
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

describe('DiscoverPage "what can we both watch" shelf (#322)', () => {
  const bothWatch: SuggestionShelf = {
    reason: 'What you can both watch',
    titles: [makeTitle('201', 'The Bear')],
    kind: 'BOTH_WATCH',
    providerFiltered: true,
  }

  it('leads the page, ahead of shelves the backend built first', async () => {
    // The backend builds BOTH_WATCH after FRANCHISE and returns it in build order;
    // the page is what puts the household's question at the top
    const franchise: SuggestionShelf = {
      reason: 'Next in the Dune Collection',
      titles: [makeTitle('301', 'Dune: Part Two')],
      kind: 'FRANCHISE',
      providerFiltered: false,
    }
    mockApi.getSuggestions.mockResolvedValue([franchise, bothWatch, shelf])

    renderPage()
    await screen.findByText('The Bear')

    const headings = screen
      .getAllByText(/What you can both watch|Next in the Dune Collection|Because you like Sci-Fi/)
      .map(el => el.textContent)
    expect(headings[0]).toContain('What you can both watch')
    expect(headings[1]).toContain('Next in the Dune Collection')
  })

  it('says the titles are on shared services, not "your" services', async () => {
    mockApi.getSuggestions.mockResolvedValue([bothWatch, shelf])

    renderPage()
    await screen.findByText('The Bear')

    expect(screen.getByText('On services you share')).toBeInTheDocument()
    expect(screen.queryByText('On your services')).not.toBeInTheDocument()
  })
})

describe('DiscoverPage "People" row on actor search (#356)', () => {
  function person(id: number, name: string, profileUrl: string | null = null) {
    return { id, name, profileUrl }
  }

  function renderSearch() {
    return render(
      <MemoryRouter initialEntries={['/?q=leo']}>
        <WatchlistProvider>
          <DiscoverPage />
        </WatchlistProvider>
      </MemoryRouter>,
    )
  }

  it('shows a People row above the title results, linking to the person page', async () => {
    mockApi.searchTitles.mockResolvedValue({
      titles: [makeTitle('27205', 'Inception')],
      people: [person(6193, 'Leonardo DiCaprio', 'https://img/leo.jpg')],
    })

    const { container } = renderSearch()

    const link = await screen.findByRole('link', { name: /Leonardo DiCaprio/ })
    expect(link).toHaveAttribute('href', '/person/6193')

    // People row is rendered before the title grid in the DOM.
    const peopleRow = container.querySelector('.people-row')!
    const titleGrid = container.querySelector('.title-grid')!
    expect(peopleRow).toBeTruthy()
    expect(titleGrid).toBeTruthy()
    expect(peopleRow.compareDocumentPosition(titleGrid))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('renders at most 4 person tiles even when more come back (CSS caps to 2 on mobile)', async () => {
    mockApi.searchTitles.mockResolvedValue({
      titles: [],
      people: [
        person(1, 'Actor One'),
        person(2, 'Actor Two'),
        person(3, 'Actor Three'),
        person(4, 'Actor Four'),
        person(5, 'Actor Five'),
        person(6, 'Actor Six'),
      ],
    })

    const { container } = renderSearch()

    await screen.findByText('Actor One')
    expect(container.querySelectorAll('.people-row-item')).toHaveLength(4)
    // The 2-vs-4 split is a CSS media query, not asserted here.
    expect(screen.queryByText('Actor Five')).not.toBeInTheDocument()
  })
})

describe('DiscoverPage genre browse (#384)', () => {
  const romcom = makeTitle('501', 'Notting Hill', { type: 'MOVIE', genreIds: [35, 10749] })
  const comedy = makeTitle('502', 'Groundhog Day', { type: 'MOVIE', genreIds: [35] })

  function renderAt(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <WatchlistProvider>
          <DiscoverPage />
        </WatchlistProvider>
      </MemoryRouter>,
    )
  }

  // The catalog fetch is what makes the trigger appear, so every flow waits on it
  async function openPanel() {
    fireEvent.click(await screen.findByRole('button', { name: /^Genres/ }))
  }

  function tick(name: string) {
    fireEvent.click(screen.getByRole('checkbox', { name }))
  }

  it('issues one browse request on Apply, not one per checkbox', async () => {
    mockApi.browseByGenre.mockResolvedValue([romcom])
    renderAt('/')
    await openPanel()

    tick('Comedy')
    tick('Romance')
    // Deferred commit (#382): ticking a box must not query. This is the assertion
    // that keeps a five-genre selection from costing five TMDB round trips.
    expect(mockApi.browseByGenre).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await screen.findByText('Notting Hill')
    expect(mockApi.browseByGenre).toHaveBeenCalledTimes(1)
    expect(mockApi.browseByGenre).toHaveBeenCalledWith(1, 'MOVIE', [35, 10749], 1)
  })

  it('renders the browse grid instead of the shelves, and never fetches both', async () => {
    mockApi.browseByGenre.mockResolvedValue([romcom])

    renderAt('/?genres=35,10749&medium=MOVIE')

    await screen.findByText('Notting Hill')
    // The shelves' own titles are gone, and the shelf compute was never asked for:
    // it costs a compute and records impressions that sink those titles tomorrow
    expect(screen.queryByText('Severance')).not.toBeInTheDocument()
    expect(mockApi.getSuggestions).not.toHaveBeenCalled()
  })

  it('goes back to the shelves when the genres are cleared', async () => {
    mockApi.browseByGenre.mockResolvedValue([romcom])
    renderAt('/?genres=35&medium=MOVIE')
    await screen.findByText('Notting Hill')

    await openPanel()
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))

    await screen.findByText('Severance')
    expect(screen.queryByText('Notting Hill')).not.toBeInTheDocument()
  })

  it('appends the next page on Load more and stops at the depth cap', async () => {
    mockApi.browseByGenre.mockImplementation((_wl: number, _type: string, _ids: number[], page: number) =>
      Promise.resolve([makeTitle(`p${page}`, `Page ${page} Title`, { type: 'MOVIE' })]))

    renderAt('/?genres=35&medium=MOVIE')
    await screen.findByText('Page 1 Title')

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByText('Page 2 Title')
    // Appends, doesn't replace
    expect(screen.getByText('Page 1 Title')).toBeInTheDocument()
    expect(mockApi.browseByGenre).toHaveBeenLastCalledWith(1, 'MOVIE', [35], 2)

    // Pages 3–6, then the button is gone: the endpoint rejects page 7, so asking
    // for it would be a guaranteed 400
    for (const page of [3, 4, 5, 6]) {
      fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
      await screen.findByText(`Page ${page} Title`)
    }
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    expect(mockApi.browseByGenre).toHaveBeenCalledTimes(6)
  })

  it('hides Load more when a page comes back empty', async () => {
    mockApi.browseByGenre.mockImplementation((_wl: number, _type: string, _ids: number[], page: number) =>
      Promise.resolve(page === 1 ? [romcom] : []))

    renderAt('/?genres=35&medium=MOVIE')
    await screen.findByText('Notting Hill')

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    // A narrow AND runs out of TMDB pages long before the cap
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument())
    expect(screen.getByText('Notting Hill')).toBeInTheDocument()
  })

  it('re-queries the other medium and offers that catalog’s real genre names', async () => {
    mockApi.browseByGenre.mockResolvedValue([romcom])
    renderAt('/?genres=35&medium=MOVIE')
    await screen.findByText('Notting Hill')

    fireEvent.click(screen.getByRole('button', { name: 'TV' }))

    await waitFor(() =>
      expect(mockApi.browseByGenre).toHaveBeenLastCalledWith(1, 'TV', [35], 1))

    // TMDB's TV catalog, not the movie one: "Sci-Fi & Fantasy" (10765) rather than
    // "Science Fiction" (878). Mapping between them is exactly what the toggle avoids.
    await openPanel()
    expect(screen.getByRole('checkbox', { name: 'Sci-Fi & Fantasy' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Science Fiction' })).not.toBeInTheDocument()
  })

  it('explains itself when every selected genre belongs to the other medium', async () => {
    // Romance is movie-only; TMDB's TV catalog has no equivalent at all
    renderAt('/?genres=10749&medium=TV')

    expect(await screen.findByText(/aren’t in TMDB’s TV catalog/)).toBeInTheDocument()
    expect(mockApi.browseByGenre).not.toHaveBeenCalled()
    // Not silently the shelves either — something was asked for and wasn't answered
    expect(screen.queryByText('Severance')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Clear genres' }))
    await screen.findByText('Severance')
  })

  it('filters search results by genre client-side, with an explicit empty state', async () => {
    mockApi.searchTitles.mockResolvedValue({
      titles: [romcom, comedy],
      people: [],
    })

    renderAt('/?q=love&genres=35,10749&medium=MOVIE')

    // Both are comedies; only one is also a romance
    await screen.findByText('Notting Hill')
    expect(screen.queryByText('Groundhog Day')).not.toBeInTheDocument()
    // The search itself is untouched — still the all-medium multi search
    expect(mockApi.searchTitles).toHaveBeenCalledWith('love')
    expect(mockApi.browseByGenre).not.toHaveBeenCalled()
  })

  it('says "no results in those genres" rather than showing an empty page', async () => {
    mockApi.searchTitles.mockResolvedValue({ titles: [comedy], people: [] })

    renderAt('/?q=day&genres=35,10749&medium=MOVIE')

    expect(await screen.findByText(/No results for “day” in Comedy \+ Romance/))
      .toBeInTheDocument()

    // One click out of the dead end, and the unfiltered result is back
    fireEvent.click(screen.getByRole('button', { name: 'Clear genres' }))
    await screen.findByText('Groundhog Day')
  })

  it('keeps ?genres= while typing in the search box', async () => {
    mockApi.browseByGenre.mockResolvedValue([romcom])
    mockApi.searchTitles.mockResolvedValue({ titles: [romcom], people: [] })
    renderAt('/?genres=35,10749&medium=MOVIE')
    await screen.findByText('Notting Hill')

    fireEvent.change(screen.getByRole('searchbox', { name: '' }), { target: { value: 'love' } })

    // The genre badge survives: writing ?q= used to replace the whole query string
    // and drop the selection on every keystroke
    await waitFor(() => expect(mockApi.searchTitles).toHaveBeenCalledWith('love'))
    expect(screen.getByRole('button', { name: /^Genres/ })).toHaveTextContent('2')
  })

  // ⚠️ This pins the #305 *property*, not the `browseSeeded` flag: the seed rides the
  // same Promise.all that produces the titles, so tiles structurally cannot paint
  // first and the test still passes with the gate deleted (verified). Same shape as
  // #363 — the gate stays as belt-and-braces, but don't read this as covering it.
  it('holds the tiles until the watchlist reconcile has landed (#305)', async () => {
    let resolveEntries: (entries: never[]) => void = () => {}
    mockApi.browseByGenre.mockResolvedValue([romcom])
    mockApi.getWatchlistEntries.mockImplementation(() =>
      new Promise(resolve => { resolveEntries = resolve as (entries: never[]) => void }))

    renderAt('/?genres=35&medium=MOVIE')

    // The browse response has landed but the entries haven't: painting now would let
    // the later reconcile revert an optimistic add to "+"
    await waitFor(() => expect(mockApi.browseByGenre).toHaveBeenCalled())
    expect(screen.queryByText('Notting Hill')).not.toBeInTheDocument()

    resolveEntries([])
    await screen.findByText('Notting Hill')
  })

  it('leaves the shelves and the search alone when no genres are selected', async () => {
    mockApi.searchTitles.mockResolvedValue({ titles: [comedy], people: [] })

    renderAt('/')
    await screen.findByText('Severance')
    expect(mockApi.browseByGenre).not.toHaveBeenCalled()

    fireEvent.change(screen.getByRole('searchbox', { name: '' }), { target: { value: 'day' } })

    await screen.findByText('Groundhog Day')
    expect(mockApi.browseByGenre).not.toHaveBeenCalled()
  })
})
