import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import RollTheDiceModal from './RollTheDiceModal'
import type { ApiClient } from '../services/api'
import type { TitleType, TonightPick, WatchlistEntryResponse } from '../types/api'

// Mock at the service layer (#287 convention): fake ApiClient via useApi.
const mockApi = {
  getTonightPicks: vi.fn(),
}

vi.mock('../contexts/AuthContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../contexts/AuthContext')>()),
  useApi: () => mockApi as unknown as ApiClient,
}))

function makeEntry(id: number, name: string, type: TitleType): WatchlistEntryResponse {
  return {
    id,
    watchlistId: 1,
    addedByUserId: 1,
    titleId: id * 10,
    externalId: String(id),
    externalSource: 'tmdb',
    name,
    type,
    posterUrl: `https://img/${id}.jpg`,
    status: 'WATCHING',
    addedAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    startedAt: null,
    completedAt: null,
    episodeProgress: null,
    myRating: null,
  }
}

// A short film, a show mid-run, and a long film with no runtime on record.
const SHORT_MOVIE = makeEntry(1, 'Paddington', 'MOVIE')
const SHOW = makeEntry(2, 'Breaking Bad', 'TV')
const UNKNOWN_MOVIE = makeEntry(3, 'Some Obscure Film', 'MOVIE')

const MOVIE_PICK: TonightPick = {
  entryId: 1, type: 'MOVIE', runtimeMinutes: 95, nextSeason: null, nextEpisode: null,
}
const SHOW_PICK: TonightPick = {
  entryId: 2, type: 'TV', runtimeMinutes: 47, nextSeason: 3, nextEpisode: 7,
}

function renderModal(entries = [SHORT_MOVIE, SHOW, UNKNOWN_MOVIE]) {
  return render(
    <RollTheDiceModal
      entries={entries}
      watchlistId={1}
      onClose={vi.fn()}
      onOpenEntry={vi.fn()}
    />,
  )
}

describe('RollTheDiceModal time windows (#359)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('narrows the picks to the titles that fit the chosen window', async () => {
    mockApi.getTonightPicks.mockResolvedValue([MOVIE_PICK, SHOW_PICK])
    renderModal()

    // Everything is on offer before a window is chosen
    expect(screen.getByAltText('Some Obscure Film')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '2h' }))

    await waitFor(() => expect(screen.getByAltText('Paddington')).toBeInTheDocument())
    expect(screen.getByAltText('Breaking Bad')).toBeInTheDocument()
    // A title the backend left out — no known runtime, so it can't be judged to fit
    expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument()
    expect(mockApi.getTonightPicks).toHaveBeenCalledWith(1, 120)
  })

  it('labels a fitting show with the next episode it would play', async () => {
    mockApi.getTonightPicks.mockResolvedValue([MOVIE_PICK, SHOW_PICK])
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: '2h' }))

    await waitFor(() => expect(screen.getByText('S3 E7 · 47m')).toBeInTheDocument())
    expect(screen.getByText('95m')).toBeInTheDocument()
  })

  it('shows a zero state rather than an error when nothing fits', async () => {
    mockApi.getTonightPicks.mockResolvedValue([])
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: '30m' }))

    await waitFor(() =>
      expect(screen.getByText(/Nothing here fits in 30 minutes/)).toBeInTheDocument(),
    )
    expect(screen.queryByText('Checking runtimes…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Roll!' })).not.toBeInTheDocument()
  })

  it('offers the single fitting title directly instead of a roll', async () => {
    mockApi.getTonightPicks.mockResolvedValue([SHOW_PICK])
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: '60m' }))

    await waitFor(() =>
      expect(screen.getByText(/Only one thing fits 60 minutes/)).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: 'Open' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Roll!' })).not.toBeInTheDocument()
  })

  it('restores the full list on "Any time" and does not refetch a window already seen', async () => {
    mockApi.getTonightPicks.mockResolvedValue([MOVIE_PICK, SHOW_PICK])
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: '2h' }))
    await waitFor(() =>
      expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument(),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Any time' }))
    expect(screen.getByAltText('Some Obscure Film')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '2h' }))
    await waitFor(() =>
      expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument(),
    )
    expect(mockApi.getTonightPicks).toHaveBeenCalledTimes(1)
  })

  it('offers a retry rather than a stuck spinner when the check fails', async () => {
    mockApi.getTonightPicks.mockRejectedValueOnce(new Error('boom'))
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: '45m' }))

    await waitFor(() =>
      expect(screen.getByText(/Couldn't check runtimes/)).toBeInTheDocument(),
    )

    mockApi.getTonightPicks.mockResolvedValue([MOVIE_PICK])
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() =>
      expect(screen.getByText(/Only one thing fits 45 minutes/)).toBeInTheDocument(),
    )
  })
})
