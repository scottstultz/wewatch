import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { clearToken, getStoredToken, isTokenValid, storeToken } from '../services/auth'
import { getCurrentUser } from '../services/api'

interface User {
  id: number
  name: string
  email: string
}

interface AuthContextType {
  token: string | null
  user: User | null
  handleCredential: (token: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<User | null>(null)

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
    }
  }, [])

  function handleCredential(newToken: string) {
    storeToken(newToken)
    setToken(newToken)
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
  }

  return (
    <AuthContext.Provider value={{ token, user, handleCredential, signOut }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
