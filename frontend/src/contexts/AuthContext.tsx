import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import {
  TOKEN_REFRESHED_EVENT,
  clearToken,
  getStoredToken,
  getTokenExpiryMs,
  isTokenValid,
  storeToken,
} from '../services/auth'
import { getCurrentUser } from '../services/api'

interface User {
  id: number
  name: string
  email: string
}

interface AuthContextType {
  token: string | null
  user: User | null
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
        .catch(() => {
          clearToken()
          setToken(null)
        })
    } else if (stored) {
      clearToken()
      setSessionExpired(true)
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

  function signOut() {
    clearToken()
    setToken(null)
    setUser(null)
    setSessionExpired(false)
  }

  return (
    <AuthContext.Provider value={{ token, user, sessionExpired, handleCredential, signOut }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
