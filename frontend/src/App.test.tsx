import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { createApiClient, exchangeToken, getCurrentUser } from './services/api'
import type { ApiClient, BackendUser } from './services/api'
import type { PersonDetailResponse, WatchlistResponse } from './types/api'

// Mock at the service layer (#287 convention): the real AuthProvider and
// WatchlistProvider run on top of a fake ApiClient. getCurrentUser and
// exchangeToken are the calls AuthContext/SignInPage make directly.
vi.mock('./services/api', async importOriginal => ({
  ...(await importOriginal<typeof import('./services/api')>()),
  getCurrentUser: vi.fn(),
  exchangeToken: vi.fn(),
  createApiClient: vi.fn(),
}))

const mockGetCurrentUser = vi.mocked(getCurrentUser)
const mockExchangeToken = vi.mocked(exchangeToken)
const mockCreateApiClient = vi.mocked(createApiClient)

const mockApi = {
  getWatchlists: vi.fn(),
  getWatchlistEntries: vi.fn(),
  getPerson: vi.fn(),
  findOrCreateTitle: vi.fn(),
  addToWatchlist: vi.fn(),
}

const TOKEN_KEY = 'wewatch_token'

const backendUser: BackendUser = {
  id: 7,
  email: 'user@example.com',
  displayName: 'Test User',
  watchRegion: null,
  watchProviderIds: null,
}

const watchlist: WatchlistResponse = {
  id: 1,
  name: 'My List',
  type: 'PERSONAL',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  members: [],
  isDefault: true,
}

const keanu: PersonDetailResponse = {
  id: 1245,
  name: 'Keanu Reeves',
  biography: 'A Canadian actor.',
  profileUrl: null,
  knownForDepartment: 'Acting',
  birthday: '1964-09-02',
  placeOfBirth: 'Beirut, Lebanon',
  credits: [],
}

/** Unsigned JWT-shaped token whose exp lies expiresInMs from now. */
function makeToken(expiresInMs: number): string {
  const payload = {
    sub: '7',
    email: 'user@example.com',
    exp: Math.round((Date.now() + expiresInMs) / 1000),
  }
  return `header.${btoa(JSON.stringify(payload))}.signature`
}

function renderAppAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  mockGetCurrentUser.mockResolvedValue(backendUser)
  mockCreateApiClient.mockReturnValue(mockApi as unknown as ApiClient)
  mockApi.getWatchlists.mockResolvedValue([watchlist])
  mockApi.getWatchlistEntries.mockResolvedValue([])
  mockApi.getPerson.mockResolvedValue(keanu)
})

describe('deep links to protected routes (#308)', () => {
  it('renders the requested page on a cold load with a valid stored token, without flashing sign-in', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(60 * 60 * 1000))

    renderAppAt('/person/1245')

    // The token is seeded synchronously, so ProtectedRoute never bounces to
    // sign-in — not even for one render.
    expect(screen.queryByText('Sign in to continue')).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Keanu Reeves' })).toBeInTheDocument()
    expect(mockApi.getPerson).toHaveBeenCalledWith(1245)
  })

  it('redirects a signed-out deep link to sign-in', () => {
    renderAppAt('/person/1245')

    expect(screen.getByText('Sign in to continue')).toBeInTheDocument()
    expect(mockApi.getPerson).not.toHaveBeenCalled()
  })

  it('returns the user to the originally requested page after signing in from the redirect', async () => {
    mockExchangeToken.mockResolvedValue(makeToken(60 * 60 * 1000))

    renderAppAt('/person/1245')

    // Bounced to sign-in first
    expect(screen.getByText('Sign in to continue')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'user@example.com' } })
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter2xx' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign In' }))

    // Lands on the requested person page, not /home
    expect(await screen.findByRole('heading', { name: 'Keanu Reeves' })).toBeInTheDocument()
    await waitFor(() => expect(mockApi.getPerson).toHaveBeenCalledWith(1245))
  })

  it('redirects a deep link with an expired stored token to sign-in', () => {
    localStorage.setItem(TOKEN_KEY, makeToken(-1000))

    renderAppAt('/person/1245')

    expect(screen.getByText('Sign in to continue')).toBeInTheDocument()
    expect(screen.getByText('Your session expired. Please sign in again.')).toBeInTheDocument()
    expect(mockApi.getPerson).not.toHaveBeenCalled()
  })
})
