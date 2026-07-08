// Shared Vitest setup, wired in vite.config.ts (test.setupFiles).
// Conventions (#287): tests are colocated with their subject as
// `<name>.test.ts(x)`; components render with Testing Library; API access is
// mocked at the service layer (the ApiClient / getCurrentUser functions),
// never by stubbing global fetch.
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

// Node 22+ ships its own experimental `localStorage` global that shadows
// jsdom's and is non-functional unless Node's --localstorage-file flag is
// set. Replace both storages with a plain in-memory implementation.
class MemoryStorage implements Storage {
  private store = new Map<string, string>()
  get length() {
    return this.store.size
  }
  key(index: number) {
    return [...this.store.keys()][index] ?? null
  }
  getItem(key: string) {
    return this.store.get(key) ?? null
  }
  setItem(key: string, value: string) {
    this.store.set(key, String(value))
  }
  removeItem(key: string) {
    this.store.delete(key)
  }
  clear() {
    this.store.clear()
  }
}
Object.defineProperty(globalThis, 'localStorage', { value: new MemoryStorage() })
Object.defineProperty(globalThis, 'sessionStorage', { value: new MemoryStorage() })

afterEach(() => {
  cleanup()
  localStorage.clear()
  sessionStorage.clear()
})

// jsdom doesn't implement ResizeObserver (used by DiscoverPage's shelf rows)
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver

// jsdom's scrollTo only logs a "not implemented" error — silence it
window.scrollTo = () => {}
