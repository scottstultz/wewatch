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
    genreIds: [],
  }
}

const PADDINGTON = makeEntry(1, 'Paddington', 'MOVIE')
const BREAKING_BAD = makeEntry(2, 'Breaking Bad', 'TV')
// Never comes back from /tonight — no runtime on record, so it can't be judged to fit
const OBSCURE = makeEntry(3, 'Some Obscure Film', 'MOVIE')
const DUNE = makeEntry(4, 'Dune', 'MOVIE')
const THE_BEAR = makeEntry(5, 'The Bear', 'TV')

const PADDINGTON_PICK: TonightPick = {
  entryId: 1, type: 'MOVIE', runtimeMinutes: 95, nextSeason: null, nextEpisode: null,
}
const BREAKING_BAD_PICK: TonightPick = {
  entryId: 2, type: 'TV', runtimeMinutes: 47, nextSeason: 3, nextEpisode: 7,
}
const DUNE_PICK: TonightPick = {
  entryId: 4, type: 'MOVIE', runtimeMinutes: 155, nextSeason: null, nextEpisode: null,
}
const BEAR_PICK: TonightPick = {
  entryId: 5, type: 'TV', runtimeMinutes: 30, nextSeason: 1, nextEpisode: 1,
}

// 3 movies, 2 shows — so the toggle opens on Movies and neither side is a sole pick.
const DEFAULT_ENTRIES = [PADDINGTON, BREAKING_BAD, OBSCURE, DUNE, THE_BEAR]

// Slider stops: 0=30m 1=45m 2=60m 3=90m 4=2h 5=Any
const STOP = { m30: '0', m45: '1', m60: '2', m120: '4', any: '5' }

function renderModal(entries = DEFAULT_ENTRIES, activeGenres: string[] = []) {
  return render(
    <RollTheDiceModal
      entries={entries}
      activeGenres={activeGenres}
      watchlistId={1}
      onClose={vi.fn()}
      onOpenEntry={vi.fn()}
    />,
  )
}

const slider = () => screen.getByRole('slider')
const moveTo = (stop: string) => fireEvent.change(slider(), { target: { value: stop } })
const toggleButton = (side: 'Movies' | 'TV') =>
  screen.getByRole('button', { name: new RegExp(`^${side} \\(`) })
const tile = (name: string) => screen.getByAltText(name).closest('button')!

// The ceiling call every open fires for the runtime labels — never the stop under test.
const stopCalls = () => mockApi.getTonightPicks.mock.calls.filter(([, m]) => m !== 600)

// Holds one window's answer open so the in-flight state can be asserted.
function deferWindow(minutes: number, otherwise: TonightPick[]) {
  let release!: (picks: TonightPick[]) => void
  const held = new Promise<TonightPick[]>(resolve => { release = resolve })
  mockApi.getTonightPicks.mockImplementation((_id: number, m: number) =>
    m === minutes ? held : Promise.resolve(otherwise))
  return { release: () => release(otherwise) }
}

describe('RollTheDiceModal — media toggle (#366)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Every open fires the ceiling call for the runtime labels
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('shows one type at a time and swaps the grid when flipped', () => {
    renderModal()

    // Opens on Movies: three of them, no shows
    expect(toggleButton('Movies')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByAltText('Paddington')).toBeInTheDocument()
    expect(screen.getByAltText('Dune')).toBeInTheDocument()
    expect(screen.queryByAltText('Breaking Bad')).not.toBeInTheDocument()

    fireEvent.click(toggleButton('TV'))

    expect(screen.getByAltText('Breaking Bad')).toBeInTheDocument()
    expect(screen.getByAltText('The Bear')).toBeInTheDocument()
    expect(screen.queryByAltText('Paddington')).not.toBeInTheDocument()
  })

  it('opens on TV when there are too few movies to roll', () => {
    renderModal([PADDINGTON, BREAKING_BAD, THE_BEAR])

    expect(toggleButton('TV')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByAltText('Breaking Bad')).toBeInTheDocument()
  })

  it('says the side is empty rather than blaming the time window', () => {
    renderModal([BREAKING_BAD, THE_BEAR])

    fireEvent.click(toggleButton('Movies'))

    expect(screen.getByText('No movies in this list.')).toBeInTheDocument()
    expect(screen.queryByText(/Nothing here fits/)).not.toBeInTheDocument()
  })
})

describe('RollTheDiceModal — duration slider (#366)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Every open fires the ceiling call for the runtime labels
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('starts on "Any", rightmost, filtering nothing', () => {
    renderModal()

    expect(slider()).toHaveValue('5')
    expect(slider()).toHaveAttribute('max', '5')
    expect(slider()).toHaveAttribute('aria-valuetext', 'Any time')
    expect(screen.getByAltText('Some Obscure Film')).toBeInTheDocument()
  })

  it('labels the tiles with their runtimes at "Any" without filtering any out', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK])
    renderModal()

    // The endpoint's own ceiling, used as a label source rather than a filter
    expect(mockApi.getTonightPicks).toHaveBeenCalledWith(1, 600)
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())
    expect(screen.getByText('155m')).toBeInTheDocument()
    // Omitted by the endpoint (no runtime on record) — still offered, just unlabelled
    expect(screen.getByAltText('Some Obscure Film')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Roll All (3 Titles)' })).toBeEnabled()
  })

  it('narrows the grid to the titles that fit the chosen stop', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK, BREAKING_BAD_PICK])
    renderModal()

    moveTo(STOP.m120)

    await waitFor(() =>
      expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument(),
    )
    expect(screen.getByAltText('Paddington')).toBeInTheDocument()
    expect(screen.getByAltText('Dune')).toBeInTheDocument()
    expect(mockApi.getTonightPicks).toHaveBeenCalledWith(1, 120)
    expect(slider()).toHaveAttribute('aria-valuetext', '2 hours')
  })

  it('labels a fitting show with the next episode it would play', async () => {
    mockApi.getTonightPicks.mockResolvedValue([BREAKING_BAD_PICK, BEAR_PICK])
    renderModal()

    fireEvent.click(toggleButton('TV'))
    moveTo(STOP.m120)

    await waitFor(() => expect(screen.getByText('S3 E7 · 47m')).toBeInTheDocument())
    expect(screen.getByText('S1 E1 · 30m')).toBeInTheDocument()
  })

  it('restores the full list on "Any" and does not refetch a stop already seen', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK])
    renderModal()

    moveTo(STOP.m120)
    await waitFor(() =>
      expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument(),
    )

    moveTo(STOP.any)
    expect(screen.getByAltText('Some Obscure Film')).toBeInTheDocument()

    moveTo(STOP.m120)
    await waitFor(() =>
      expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument(),
    )
    // Ignoring the ceiling call fired at open, the 2h stop was fetched exactly once
    expect(mockApi.getTonightPicks.mock.calls.filter(([, m]) => m === 120)).toHaveLength(1)
  })

  it('shows a zero state rather than an error when nothing fits', async () => {
    mockApi.getTonightPicks.mockResolvedValue([])
    renderModal()

    moveTo(STOP.m30)

    await waitFor(() =>
      expect(screen.getByText(/Nothing here fits in 30 minutes/)).toBeInTheDocument(),
    )
    expect(screen.queryByText('Checking runtimes…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Roll All/ })).not.toBeInTheDocument()
  })

  it('offers the single fitting title directly instead of a roll', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK])
    renderModal()

    moveTo(STOP.m60)

    await waitFor(() =>
      expect(screen.getByText(/Only one thing fits 60 minutes/)).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: 'Open' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Roll/ })).not.toBeInTheDocument()
    // The filters stay reachable so the sole pick isn't a dead end
    expect(slider()).toBeInTheDocument()
    expect(toggleButton('TV')).toBeInTheDocument()
  })

  it('offers a retry rather than a stuck spinner when the check fails', async () => {
    mockApi.getTonightPicks.mockImplementation((_id: number, minutes: number) =>
      minutes === 45 ? Promise.reject(new Error('boom')) : Promise.resolve([]),
    )
    renderModal()

    moveTo(STOP.m45)

    await waitFor(() =>
      expect(screen.getByText(/Couldn't check runtimes/)).toBeInTheDocument(),
    )

    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK])
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() =>
      expect(screen.getByText(/Only one thing fits 45 minutes/)).toBeInTheDocument(),
    )
  })
})

describe('RollTheDiceModal — roll CTA (#366)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Every open fires the ceiling call for the runtime labels
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('rolls everything by default and tracks the selection as it grows', () => {
    renderModal()

    // Nothing selected: the whole matching set is the offer
    expect(screen.getByRole('button', { name: 'Roll All (3 Titles)' })).toBeEnabled()

    fireEvent.click(screen.getByAltText('Paddington'))
    const nudge = screen.getByRole('button', { name: 'Select 1 more to roll…' })
    expect(nudge).toBeDisabled()

    fireEvent.click(screen.getByAltText('Dune'))
    expect(screen.getByRole('button', { name: 'Roll Selected (2)' })).toBeEnabled()
  })

  it('cannot roll a side that has fewer than two titles', () => {
    // 1 movie, 2 shows — opens on the rollable side
    renderModal([PADDINGTON, BREAKING_BAD, THE_BEAR])

    expect(screen.getByRole('button', { name: 'Roll All (2 Titles)' })).toBeEnabled()

    fireEvent.click(toggleButton('Movies'))
    expect(screen.getByRole('button', { name: 'Roll All (1 Title)' })).toBeDisabled()
  })

  it('reaches the reveal from "Roll All" with nothing selected', () => {
    // Reduced motion lands the reveal immediately instead of running the shuffle timers
    window.matchMedia = vi.fn().mockReturnValue({ matches: true }) as unknown as typeof window.matchMedia
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: 'Roll All (3 Titles)' }))

    expect(screen.getByText('You should watch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Roll again' })).toBeInTheDocument()
  })
})

describe('RollTheDiceModal — no selection cap (#372)', () => {
  // Seven movies: one more than the cap #372 removed, so the 7th click is the assertion.
  const SEVEN_MOVIES = ['Arrival', 'Brazil', 'Coco', 'Dune', 'Elf', 'Fargo', 'Gattaca']
    .map((name, i) => makeEntry(10 + i, name, 'MOVIE'))

  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('lets a selection grow past six, with no tile disabled for being late to it', () => {
    renderModal(SEVEN_MOVIES)

    for (const entry of SEVEN_MOVIES) fireEvent.click(tile(entry.name!))

    // Pre-#372 the 7th click was a no-op and the unpicked tiles greyed out at six
    expect(screen.getByRole('button', { name: 'Roll Selected (7)' })).toBeEnabled()
    for (const entry of SEVEN_MOVIES) expect(tile(entry.name!)).toBeEnabled()
  })

  it('counts the selection without counting towards a ceiling', () => {
    renderModal(SEVEN_MOVIES)

    for (const entry of SEVEN_MOVIES) fireEvent.click(tile(entry.name!))

    expect(screen.getByText('7 selected')).toBeInTheDocument()
    expect(screen.queryByText(/\d+\s*\/\s*\d+ selected/)).not.toBeInTheDocument()
  })
})

describe('RollTheDiceModal — slider does not resize the modal (#368)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('fires one request for the stop the drag lands on, not one per stop crossed', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK])
    renderModal()

    // A range input fires onChange for every discrete step the thumb passes over
    for (const stop of ['4', '3', '2', '1', STOP.m30]) moveTo(stop)

    await waitFor(() => expect(mockApi.getTonightPicks).toHaveBeenCalledWith(1, 30))
    expect(stopCalls()).toEqual([[1, 30]])
  })

  it('dims the grid in place instead of unmounting the body while a check is in flight', async () => {
    const held = deferWindow(30, [PADDINGTON_PICK, DUNE_PICK])
    renderModal()
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())

    moveTo(STOP.m30)
    await waitFor(() => expect(screen.getByText('Checking runtimes…')).toBeInTheDocument())

    // The three things the pre-#368 body swap took down with it
    expect(screen.getByPlaceholderText('Search titles…')).toBeInTheDocument()
    expect(screen.getByAltText('Paddington')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Roll All/ })).toBeInTheDocument()

    held.release()
    await waitFor(() => expect(screen.queryByText('Checking runtimes…')).not.toBeInTheDocument())
  })

  it('cannot select or roll the previous stop\'s tiles while a check is in flight', async () => {
    const held = deferWindow(30, [PADDINGTON_PICK, DUNE_PICK])
    renderModal()
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())

    moveTo(STOP.m30)
    await waitFor(() => expect(screen.getByText('Checking runtimes…')).toBeInTheDocument())

    // These are the *old* stop's tiles and some won't survive the new one
    expect(tile('Paddington')).toBeDisabled()
    expect(screen.getByRole('button', { name: /^Roll All/ })).toBeDisabled()

    held.release()
    await waitFor(() => expect(tile('Paddington')).toBeEnabled())
  })

  it('keeps the selection when the slider is dragged away and back to the same stop', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK])
    renderModal()
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())

    fireEvent.click(tile('Paddington'))
    fireEvent.click(tile('Dune'))
    expect(screen.getByRole('button', { name: 'Roll Selected (2)' })).toBeInTheDocument()

    // Passes over other stops and returns before any of them settles
    moveTo(STOP.m60)
    moveTo(STOP.any)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Roll Selected (2)' })).toBeEnabled())
    expect(stopCalls()).toEqual([])
  })

  it('still drops the selection when the slider settles on a different stop', async () => {
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK, DUNE_PICK])
    renderModal()
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())

    fireEvent.click(tile('Paddington'))
    fireEvent.click(tile('Dune'))
    expect(screen.getByRole('button', { name: 'Roll Selected (2)' })).toBeInTheDocument()

    moveTo(STOP.m120)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Roll All (2 Titles)' })).toBeInTheDocument())
  })
})

describe('RollTheDiceModal — inherited genre filter (#383)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getTonightPicks.mockResolvedValue([])
  })

  it('names the inherited genres and counts what it was handed', () => {
    renderModal([PADDINGTON, DUNE, BREAKING_BAD], ['Comedy', 'Romance'])

    expect(screen.getByText('Comedy + Romance · 3 titles')).toBeInTheDocument()
  })

  it('reads as one title in the singular', () => {
    renderModal([PADDINGTON], ['Comedy'])

    expect(screen.getByText('Comedy · 1 title')).toBeInTheDocument()
  })

  it('renders no caption when no filter is active', () => {
    renderModal()

    // The CTA's own count is parenthesised ("Roll All (3 Titles)"), so this can only match
    // the caption.
    expect(screen.queryByText(/· \d+ titles?$/)).not.toBeInTheDocument()
  })

  it('describes the filter rather than the grid, across the toggle and the slider', async () => {
    // Only Paddington comes back for any window, so narrowing to 30m leaves the Movies side
    // with one title and the TV side with none — while the caption must not budge.
    mockApi.getTonightPicks.mockResolvedValue([PADDINGTON_PICK])
    renderModal([PADDINGTON, DUNE, BREAKING_BAD], ['Comedy', 'Romance'])
    await waitFor(() => expect(screen.getByText('95m')).toBeInTheDocument())

    fireEvent.click(toggleButton('TV'))
    expect(screen.getByText('Comedy + Romance · 3 titles')).toBeInTheDocument()

    fireEvent.click(toggleButton('Movies'))
    moveTo(STOP.m30)

    // The stop landed: the toggle now counts one movie, the caption still counts the filter
    await waitFor(() => expect(toggleButton('Movies')).toHaveTextContent('Movies (1)'))
    expect(screen.getByText('Comedy + Romance · 3 titles')).toBeInTheDocument()
  })

  it('offers only the narrowed set to roll', () => {
    renderModal([PADDINGTON, DUNE], ['Comedy'])

    expect(screen.getByAltText('Paddington')).toBeInTheDocument()
    expect(screen.getByAltText('Dune')).toBeInTheDocument()
    // Excluded by the Library's filter before it ever reached the modal
    expect(screen.queryByAltText('Some Obscure Film')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Roll All (2 Titles)' })).toBeEnabled()
  })

  it('blames the genres, not the list, for an empty side of the toggle', () => {
    renderModal([BREAKING_BAD, THE_BEAR], ['Comedy'])

    fireEvent.click(toggleButton('Movies'))

    expect(screen.getByText('No movies match those genres.')).toBeInTheDocument()
    // There *are* movies on this list — they just aren't comedies
    expect(screen.queryByText('No movies in this list.')).not.toBeInTheDocument()
  })
})
