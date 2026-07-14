import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import SignInPage from './SignInPage'
import { AuthProvider } from '../contexts/AuthContext'
import { exchangeToken } from '../services/api'

// Mocked at the service layer (#287 convention): the real AuthProvider runs on top.
vi.mock('../services/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  exchangeToken: vi.fn(),
  getCurrentUser: vi.fn(),
  createApiClient: vi.fn(),
}))

const mockExchangeToken = vi.mocked(exchangeToken)

// Google Identity Services is a global the page calls into. Stub it and capture the callback it
// registers, so a test can drive the sign-in the way a real Google credential response would.
type GoogleCallback = (response: { credential: string }) => void

function stubGoogleIdentityServices(): () => GoogleCallback {
  let captured: GoogleCallback | null = null
  ;(globalThis as unknown as { google: unknown }).google = {
    accounts: {
      id: {
        initialize: (config: { callback: GoogleCallback }) => {
          captured = config.callback
        },
        renderButton: () => {},
        revoke: () => {},
      },
    },
  }
  return () => {
    if (!captured) throw new Error('SignInPage never registered a Google callback')
    return captured
  }
}

function withError(message: string, status: number): Error {
  const err = new Error(message) as Error & { status: number }
  err.status = status
  return err
}

describe('SignInPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  // #342: the backend refuses to link a Google identity onto an account that already holds a
  // password. The page has to say so — the generic "Sign-in failed" leaves a user who already has a
  // working password with no idea that it is the way in.
  it('tells the user to use their password when Google sign-in hits a link conflict', async () => {
    const googleCallback = stubGoogleIdentityServices()
    mockExchangeToken.mockRejectedValue(
      withError('An account with this email already exists. Sign in with your password instead.', 409),
    )

    render(
      <MemoryRouter>
        <AuthProvider>
          <SignInPage />
        </AuthProvider>
      </MemoryRouter>,
    )

    await googleCallback()({ credential: 'google-credential' })

    expect(
      await screen.findByText(
        'An account with this email already exists. Sign in with your password instead.',
      ),
    ).toBeInTheDocument()
  })

  it('still shows the generic failure for any other Google sign-in error', async () => {
    const googleCallback = stubGoogleIdentityServices()
    mockExchangeToken.mockRejectedValue(withError('Token exchange failed: 500', 500))

    render(
      <MemoryRouter>
        <AuthProvider>
          <SignInPage />
        </AuthProvider>
      </MemoryRouter>,
    )

    await googleCallback()({ credential: 'google-credential' })

    expect(await screen.findByText('Sign-in failed. Please try again.')).toBeInTheDocument()
  })
})
