import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import ProfilePage from './ProfilePage'
import type { ApiClient, BackendUser } from '../services/api'
import type { WatchProvider, WatchRegion } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi.
const mockApi = {
  getMe: vi.fn(),
  getWatchRegions: vi.fn(),
  getWatchProviders: vi.fn(),
  updateStreamingSettings: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
}))

const user: BackendUser = {
  id: 1,
  email: 'user@example.com',
  displayName: 'Test User',
  watchRegion: 'US',
  watchProviderIds: [],
}

const regions: WatchRegion[] = [{ code: 'US', name: 'United States' }]

function makeProvider(id: number): WatchProvider {
  return { id, name: `Service ${id}`, logoUrl: null, displayPriority: id }
}

async function renderProfile(providers: WatchProvider[], availableRegions: WatchRegion[] = regions) {
  mockApi.getMe.mockResolvedValue(user)
  mockApi.getWatchRegions.mockResolvedValue(availableRegions)
  mockApi.getWatchProviders.mockResolvedValue(providers)
  render(<ProfilePage />)
  await screen.findByRole('group', { name: 'Streaming services' })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ProfilePage streaming service search (#299)', () => {
  it('does not render a search box for a small provider catalog', async () => {
    await renderProfile([makeProvider(1), makeProvider(2)])

    expect(screen.queryByPlaceholderText('Search streaming services…')).not.toBeInTheDocument()
  })

  it('filters the grid case-insensitively by name and hides "Show all" while searching', async () => {
    const providers = Array.from({ length: 40 }, (_, i) => makeProvider(i + 1))
    providers[24] = { id: 999, name: 'Paramount Plus', logoUrl: null, displayPriority: 999 }
    await renderProfile(providers)

    expect(screen.getByText('Show all 40 services')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Search streaming services…'), { target: { value: 'paramount' } })

    expect(screen.getByText('Paramount Plus')).toBeInTheDocument()
    expect(screen.queryByText('Service 1')).not.toBeInTheDocument()
    expect(screen.queryByText('Show all 40 services')).not.toBeInTheDocument()
  })

  it('shows an empty-state message when the query matches nothing', async () => {
    await renderProfile(Array.from({ length: 40 }, (_, i) => makeProvider(i + 1)))

    fireEvent.change(screen.getByPlaceholderText('Search streaming services…'), { target: { value: 'nonexistent' } })

    expect(screen.getByText('No streaming services match "nonexistent".')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Streaming services' })).not.toBeInTheDocument()
  })

  it('restores the prior collapsed list when the query is cleared', async () => {
    await renderProfile(Array.from({ length: 40 }, (_, i) => makeProvider(i + 1)))

    const input = screen.getByPlaceholderText('Search streaming services…')
    fireEvent.change(input, { target: { value: 'Service 1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }))

    expect(input).toHaveValue('')
    expect(screen.getByText('Show all 40 services')).toBeInTheDocument()
  })

  it('resets the search query when the region changes', async () => {
    const secondRegion = { code: 'CA', name: 'Canada' }
    await renderProfile(Array.from({ length: 40 }, (_, i) => makeProvider(i + 1)), [...regions, secondRegion])

    fireEvent.change(screen.getByPlaceholderText('Search streaming services…'), { target: { value: 'Service 1' } })
    fireEvent.change(screen.getByLabelText('Country:'), { target: { value: 'CA' } })

    await screen.findByRole('group', { name: 'Streaming services' })
    expect(screen.queryByPlaceholderText('Search streaming services…')).toHaveValue('')
  })
})
