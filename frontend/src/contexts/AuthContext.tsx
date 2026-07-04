import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  TOKEN_REFRESHED_EVENT,
  clearToken,
  getStoredToken,
  getTokenExpiryMs,
  isTokenValid,
  storeToken,
} from '../services/auth'
import { UnauthorizedError, createApiClient, getCurrentUser } from '../services/api'
import type { ApiClient } from '../services/api'

interface User {
  id: number
  name: string
  email: string
}

interface AuthContextType {
  token: string | null
  user: User | null
  api: ApiClient | null
  sessionExpired: boolean
  handleCredential: (token: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [sessionExpired, setSessionExpired] = useState(false)

  useEffect(() => {
    const stored = getStoredToken()
    if (stored && isTokenValid(stored)) {
      setToken(stored)
      getCurrentUser(stored)
        .then(backendUser => setUser({ id: backendUser.id, name: backendUser.displayName, email: backendUser.email }))
        .catch(err => {
          // Only a confirmed 401 means the token is bad. A transient network
          // error (e.g. mobile reconnecting after a resume) must not force a
          // sign-out — keep the token and let the next request retry (#242).
          if (err instanceof UnauthorizedError) {
            clearToken()
            setToken(null)
          }
        })
    } else if (stored) {
      clearToken()
      setSessionExpired(true)
    }
  }, [])

  // Re-validate the token whenever the tab is restored (#242). On mobile,
  // closing the browser freezes/bfcaches the page rather than reloading it, so
  // neither the bootstrap effect nor the expiry timer below run on restore —
  // the app could resume with an already-expired token. pageshow (bfcache) and
  // visibilitychange (foregrounding) cover the restore paths; an expired token
  // routes the user to sign-in with the "session expired" notice.
  useEffect(() => {
    function revalidate() {
      if (document.visibilityState === 'hidden') return
      const stored = getStoredToken()
      if (stored && !isTokenValid(stored)) {
        clearToken()
        setToken(null)
        setUser(null)
        setSessionExpired(true)
      }
    }
    window.addEventListener('pageshow', revalidate)
    document.addEventListener('visibilitychange', revalidate)
    return () => {
      window.removeEventListener('pageshow', revalidate)
      document.removeEventListener('visibilitychange', revalidate)
    }
  }, [])

  // Adopt tokens the backend rotates mid-session (X-Refreshed-Token header)
  useEffect(() => {
    function onTokenRefreshed(event: Event) {
      setToken((event as CustomEvent<string>).detail)
    }
    window.addEventListener(TOKEN_REFRESHED_EVENT, onTokenRefreshed)
    return () => window.removeEventListener(TOKEN_REFRESHED_EVENT, onTokenRefreshed)
  }, [])

  // Sign out gracefully the moment the current token expires (only reached
  // when the session was idle too long for the sliding refresh to kick in)
  useEffect(() => {
    if (!token) return
    const expiresAt = getTokenExpiryMs(token)
    if (expiresAt == null) return
    const timer = setTimeout(() => {
      clearToken()
      setToken(null)
      setUser(null)
      setSessionExpired(true)
    }, Math.max(expiresAt - Date.now(), 0))
    return () => clearTimeout(timer)
  }, [token])

  function handleCredential(newToken: string) {
    storeToken(newToken)
    setToken(newToken)
    setSessionExpired(false)
    getCurrentUser(newToken)
      .then(backendUser => setUser({ id: backendUser.id, name: backendUser.displayName, email: backendUser.email }))
      .catch(() => {
        clearToken()
        setToken(null)
      })
  }

  const signOut = useCallback(() => {
    clearToken()
    setToken(null)
    setUser(null)
    setSessionExpired(false)
  }, [])

  // Single place that handles 401s: the client signs out centrally, and
  // ProtectedRoute redirects to /sign-in once the token clears.
  const api = useMemo(
    () => (token ? createApiClient(token, signOut) : null),
    [token, signOut],
  )

  return (
    <AuthContext.Provider value={{ token, user, api, sessionExpired, handleCredential, signOut }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

/**
 * The authenticated API client. Only usable under ProtectedRoute, where a
 * token is guaranteed; the client is recreated whenever the token rotates.
 */
export function useApi(): ApiClient {
  const { api } = useAuth()
  if (!api) throw new Error('useApi requires an authenticated session')
  return api
}
