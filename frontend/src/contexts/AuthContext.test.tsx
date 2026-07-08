import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { AuthProvider, useAuth } from './AuthContext'
import { getCurrentUser, UnauthorizedError } from '../services/api'
import { notifyTokenRefreshed } from '../services/auth'
import type { BackendUser } from '../services/api'

// Mock at the service layer (#287 convention): getCurrentUser is the only
// network call AuthContext makes itself; UnauthorizedError stays real so
// instanceof checks in the provider keep working.
vi.mock('../services/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  getCurrentUser: vi.fn(),
}))

const mockGetCurrentUser = vi.mocked(getCurrentUser)

const TOKEN_KEY = 'wewatch_token'

const backendUser: BackendUser = {
  id: 7,
  email: 'user@example.com',
  displayName: 'Test User',
  watchRegion: null,
  watchProviderIds: null,
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

function Probe() {
  const { token, user, sessionExpired } = useAuth()
  return (
    <>
      <span data-testid="token">{token ?? 'none'}</span>
      <span data-testid="user">{user?.email ?? 'none'}</span>
      <span data-testid="expired">{String(sessionExpired)}</span>
    </>
  )
}

function renderAuth() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

beforeEach(() => {
  mockGetCurrentUser.mockReset()
  mockGetCurrentUser.mockResolvedValue(backendUser)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('AuthContext bootstrap', () => {
  it('adopts a valid stored token and loads the user', async () => {
    const token = makeToken(60 * 60 * 1000)
    localStorage.setItem(TOKEN_KEY, token)

    renderAuth()

    expect(screen.getByTestId('token')).toHaveTextContent(token)
    await waitFor(() =>
      expect(screen.getByTestId('user')).toHaveTextContent('user@example.com'),
    )
    expect(mockGetCurrentUser).toHaveBeenCalledWith(token)
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('starts signed out when no token is stored', () => {
    renderAuth()

    expect(screen.getByTestId('token')).toHaveTextContent('none')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
    expect(mockGetCurrentUser).not.toHaveBeenCalled()
  })

  it('clears an expired stored token and flags the session as expired', () => {
    localStorage.setItem(TOKEN_KEY, makeToken(-1000))

    renderAuth()

    expect(screen.getByTestId('token')).toHaveTextContent('none')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(mockGetCurrentUser).not.toHaveBeenCalled()
  })

  it('clears the token when the user fetch fails with a confirmed 401', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(60 * 60 * 1000))
    mockGetCurrentUser.mockRejectedValue(new UnauthorizedError())

    renderAuth()

    await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('none'))
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('keeps the token when the user fetch fails with a transient network error (#242)', async () => {
    const token = makeToken(60 * 60 * 1000)
    localStorage.setItem(TOKEN_KEY, token)
    mockGetCurrentUser.mockRejectedValue(new TypeError('Failed to fetch'))

    renderAuth()

    await waitFor(() => expect(mockGetCurrentUser).toHaveBeenCalled())
    // Let the rejection propagate through the .catch handler
    await act(async () => {})
    expect(screen.getByTestId('token')).toHaveTextContent(token)
    expect(screen.getByTestId('user')).toHaveTextContent('none')
    expect(localStorage.getItem(TOKEN_KEY)).toBe(token)
  })
})

describe('AuthContext tab restore (#242)', () => {
  // The frozen-tab scenario: the token expired while the page was bfcached,
  // so neither the bootstrap effect nor the expiry timer ran. Swapping the
  // stored token for an expired one reproduces that state without clock
  // manipulation — revalidate() reads storage, not React state.
  it('signs out gracefully on pageshow when the stored token has expired', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(60 * 60 * 1000))
    renderAuth()
    await waitFor(() =>
      expect(screen.getByTestId('user')).toHaveTextContent('user@example.com'),
    )

    localStorage.setItem(TOKEN_KEY, makeToken(-1000))
    act(() => {
      window.dispatchEvent(new Event('pageshow'))
    })

    expect(screen.getByTestId('token')).toHaveTextContent('none')
    expect(screen.getByTestId('user')).toHaveTextContent('none')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('revalidates on visibilitychange only once the tab is visible', async () => {
    const token = makeToken(60 * 60 * 1000)
    localStorage.setItem(TOKEN_KEY, token)
    renderAuth()
    await waitFor(() =>
      expect(screen.getByTestId('user')).toHaveTextContent('user@example.com'),
    )
    localStorage.setItem(TOKEN_KEY, makeToken(-1000))

    // Hidden tab: revalidation must not run (the user isn't looking yet)
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'hidden',
    })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(screen.getByTestId('token')).toHaveTextContent(token)

    // Foregrounded: the expired stored token routes to the sign-out path
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(screen.getByTestId('token')).toHaveTextContent('none')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
  })

  it('keeps a still-valid token on pageshow', async () => {
    const token = makeToken(60 * 60 * 1000)
    localStorage.setItem(TOKEN_KEY, token)
    renderAuth()
    await waitFor(() =>
      expect(screen.getByTestId('user')).toHaveTextContent('user@example.com'),
    )

    act(() => {
      window.dispatchEvent(new Event('pageshow'))
    })

    expect(screen.getByTestId('token')).toHaveTextContent(token)
    expect(screen.getByTestId('user')).toHaveTextContent('user@example.com')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })
})

describe('AuthContext expiry timer', () => {
  it('signs out gracefully the moment the token expires', async () => {
    vi.useFakeTimers()
    const token = makeToken(5 * 60 * 1000)
    localStorage.setItem(TOKEN_KEY, token)

    renderAuth()
    // Flush the getCurrentUser microtasks without advancing the clock
    await act(async () => {})
    expect(screen.getByTestId('user')).toHaveTextContent('user@example.com')

    act(() => {
      // exp is rounded to whole seconds, so overshoot by one
      vi.advanceTimersByTime(5 * 60 * 1000 + 1000)
    })

    expect(screen.getByTestId('token')).toHaveTextContent('none')
    expect(screen.getByTestId('user')).toHaveTextContent('none')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })
})

describe('AuthContext token refresh', () => {
  it('adopts a token rotated via the wewatch:token-refreshed event', async () => {
    localStorage.setItem(TOKEN_KEY, makeToken(60 * 60 * 1000))
    renderAuth()
    await waitFor(() =>
      expect(screen.getByTestId('user')).toHaveTextContent('user@example.com'),
    )

    const rotated = makeToken(2 * 60 * 60 * 1000)
    act(() => {
      notifyTokenRefreshed(rotated)
    })

    expect(screen.getByTestId('token')).toHaveTextContent(rotated)
    expect(localStorage.getItem(TOKEN_KEY)).toBe(rotated)
    // The user stays signed in — refresh is invisible
    expect(screen.getByTestId('user')).toHaveTextContent('user@example.com')
  })
})
