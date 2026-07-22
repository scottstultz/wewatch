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
  searchTitles: vi.fn(),
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
  mockApi.searchTitles.mockResolvedValue({ titles: [], people: [] })
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
