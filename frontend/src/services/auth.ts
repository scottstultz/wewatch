const TOKEN_KEY = 'wewatch_token'

interface JwtPayload {
  sub: string
  email: string
  name: string
  exp: number
}

export function decodeToken(token: string): JwtPayload | null {
  try {
    return JSON.parse(atob(token.split('.')[1])) as JwtPayload
  } catch {
    return null
  }
}

export function isTokenValid(token: string): boolean {
  const payload = decodeToken(token)
  return payload !== null && payload.exp * 1000 > Date.now()
}

export function storeToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}
