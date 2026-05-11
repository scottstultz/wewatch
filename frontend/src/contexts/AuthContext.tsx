import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { clearToken, decodeToken, getStoredToken, isTokenValid, storeToken } from '../services/auth'

interface User {
  name: string
  email: string
}

interface AuthContextType {
  token: string | null
  user: User | null
  handleCredential: (credential: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<User | null>(null)

  useEffect(() => {
    const stored = getStoredToken()
    if (stored && isTokenValid(stored)) {
      const payload = decodeToken(stored)!
      setToken(stored)
      setUser({ name: payload.name, email: payload.email })
    }
  }, [])

  function handleCredential(credential: string) {
    const payload = decodeToken(credential)
    if (!payload) return
    storeToken(credential)
    setToken(credential)
    setUser({ name: payload.name, email: payload.email })
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
