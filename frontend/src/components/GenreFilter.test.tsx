import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import GenreFilter from './GenreFilter'
import type { Genre, TitleType } from '../types/api'

// Deliberately includes the long TMDB names the panel has to tolerate (#382).
const OPTIONS: Genre[] = [
  { id: 10759, name: 'Action & Adventure' },
  { id: 35, name: 'Comedy' },
  { id: 18, name: 'Drama' },
  { id: 10749, name: 'Romance' },
  { id: 878, name: 'Science Fiction' },
]

function setup(selected: number[] = []) {
  const onApply = vi.fn()
  render(<GenreFilter options={OPTIONS} selected={selected} onApply={onApply} />)
  return { onApply }
}

const trigger = () => screen.getByRole('button', { name: /^Genres/ })
const box = (name: string) => screen.getByRole('checkbox', { name })

// TMDB's real split (#398, matching #384's fixture): Comedy (35) is in both
// catalogs, Romance and Science Fiction are movie-only, and TV's nearest
// equivalent is a different genre with a different id.
const MOVIE_OPTIONS: Genre[] = [
  { id: 35, name: 'Comedy' },
  { id: 10749, name: 'Romance' },
  { id: 878, name: 'Science Fiction' },
]
const TV_OPTIONS: Genre[] = [
  { id: 35, name: 'Comedy' },
  { id: 10765, name: 'Sci-Fi & Fantasy' },
]
const MEDIUM_OPTIONS: Record<TitleType, Genre[]> = { MOVIE: MOVIE_OPTIONS, TV: TV_OPTIONS }

function setupMedium(medium: TitleType = 'MOVIE', selected: number[] = []) {
  const onApply = vi.fn()
  render(
    <GenreFilter
      options={MEDIUM_OPTIONS[medium]}
      selected={selected}
      medium={medium}
      mediumOptions={MEDIUM_OPTIONS}
      onApply={onApply}
    />,
  )
  return { onApply }
}

const mediumTrigger = () => screen.getByRole('button', { name: /^(Genres|Movies •|TV •)/ })
const mediumTab = (name: string) => screen.getByRole('button', { name })

describe('GenreFilter (#382)', () => {
  it('opens the panel on click and reports it through aria-expanded', () => {
    setup()

    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('checkbox', { name: 'Comedy' })).not.toBeInTheDocument()

    fireEvent.click(trigger())

    expect(trigger()).toHaveAttribute('aria-expanded', 'true')
    expect(box('Comedy')).toBeInTheDocument()
    expect(box('Action & Adventure')).toBeInTheDocument()
  })

  it('closes on an outside click', () => {
    setup()
    fireEvent.click(trigger())
    expect(box('Comedy')).toBeInTheDocument()

    fireEvent.mouseDown(document.body)

    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('checkbox', { name: 'Comedy' })).not.toBeInTheDocument()
  })

  it('closes on Escape', () => {
    setup()
    fireEvent.click(trigger())

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('checkbox', { name: 'Comedy' })).not.toBeInTheDocument()
  })

  it('narrows the checkbox list by substring', () => {
    setup()
    fireEvent.click(trigger())

    fireEvent.change(screen.getByRole('searchbox', { name: 'Find a genre' }), {
      target: { value: 'sci' },
    })

    expect(box('Science Fiction')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Comedy' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Drama' })).not.toBeInTheDocument()
  })

  // The load-bearing one: deferred commit is what lets #384 map one Apply to one TMDB
  // request rather than one per checkbox.
  it('does not commit when a checkbox is ticked — only Apply does', () => {
    const { onApply } = setup()
    fireEvent.click(trigger())

    fireEvent.click(box('Comedy'))
    fireEvent.click(box('Romance'))

    expect(box('Comedy')).toBeChecked()
    expect(box('Romance')).toBeChecked()
    expect(onApply).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply.mock.calls[0][0].slice().sort((a: number, b: number) => a - b))
      .toEqual([35, 10749])
    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
  })

  it('commits an empty selection when Clear is pressed', () => {
    const { onApply } = setup([35, 18])
    fireEvent.click(trigger())
    expect(box('Comedy')).toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))

    expect(onApply).toHaveBeenCalledWith([])
    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
  })

  it('seeds the checkboxes from the committed selection', () => {
    setup([18])
    fireEvent.click(trigger())

    expect(box('Drama')).toBeChecked()
    expect(box('Comedy')).not.toBeChecked()
  })

  it('drops uncommitted ticks when the panel is reopened', () => {
    setup([18])
    fireEvent.click(trigger())
    fireEvent.click(box('Comedy'))
    expect(box('Comedy')).toBeChecked()

    // Abandon rather than apply
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(trigger())

    expect(box('Comedy')).not.toBeChecked()
    expect(box('Drama')).toBeChecked()
  })

  it('badges the trigger with the selected count and labels it for a screen reader', () => {
    setup([35, 18])

    expect(screen.getByLabelText('2 selected')).toHaveTextContent('2')
  })

  it('shows no badge when nothing is selected', () => {
    setup()

    expect(screen.queryByLabelText(/selected$/)).not.toBeInTheDocument()
    expect(trigger()).toHaveTextContent(/^Genres/)
  })
})

describe('GenreFilter medium mode (#398)', () => {
  it('reads "Genres" with nothing selected', () => {
    setupMedium('MOVIE', [])
    expect(mediumTrigger()).toHaveTextContent(/^Genres/)
  })

  it('reads "Movies • one genre" for a single selection', () => {
    setupMedium('MOVIE', [35])
    expect(mediumTrigger()).toHaveTextContent('Movies • Comedy')
  })

  it('reads "TV • N Genres" for two or more, and drops the .nav-badge count', () => {
    setupMedium('TV', [35, 10765])

    expect(mediumTrigger()).toHaveTextContent('TV • 2 Genres')
    expect(screen.queryByLabelText(/selected$/)).not.toBeInTheDocument()
  })

  it('renders a Movies/TV switch above the checkboxes, offering the committed medium’s catalog', () => {
    setupMedium('MOVIE')
    fireEvent.click(mediumTrigger())

    const group = screen.getByRole('group', { name: 'Medium' })
    expect(group).toBeInTheDocument()
    expect(mediumTab('Movies')).toHaveAttribute('aria-pressed', 'true')
    expect(mediumTab('TV')).toHaveAttribute('aria-pressed', 'false')
    expect(box('Science Fiction')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Sci-Fi & Fantasy' })).not.toBeInTheDocument()
  })

  // The load-bearing one, same reasoning as the checkbox case above: with a
  // selection already applied, committing on the toggle would re-fire
  // browseByGenre on every click (#398).
  it('swaps the offered checkboxes on click but does not commit or call onApply', () => {
    const { onApply } = setupMedium('MOVIE')
    fireEvent.click(mediumTrigger())

    fireEvent.click(mediumTab('TV'))

    expect(mediumTab('TV')).toHaveAttribute('aria-pressed', 'true')
    expect(box('Sci-Fi & Fantasy')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Science Fiction' })).not.toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('keeps a drafted genre present in both catalogs and drops the rest, naming them in a notice', () => {
    setupMedium('MOVIE', [35, 10749])
    fireEvent.click(mediumTrigger())
    expect(box('Comedy')).toBeChecked()

    fireEvent.click(mediumTab('TV'))

    // Comedy (35) exists in both catalogs and survives; Romance (10749) has no TV
    // equivalent TMDB publishes, so it's dropped rather than mapped by hand.
    expect(box('Comedy')).toBeChecked()
    expect(screen.queryByRole('checkbox', { name: 'Romance' })).not.toBeInTheDocument()
    expect(screen.getByText('Kept Comedy · Romance isn’t a TV genre')).toBeInTheDocument()
  })

  it('applies the drafted medium alongside the genres only when Apply is pressed', () => {
    const { onApply } = setupMedium('MOVIE')
    fireEvent.click(mediumTrigger())
    fireEvent.click(box('Comedy'))
    fireEvent.click(mediumTab('TV'))
    expect(onApply).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith([35], 'TV')
  })

  it('commits the drafted medium on Clear too, dropping the selection', () => {
    const { onApply } = setupMedium('MOVIE', [35])
    fireEvent.click(mediumTrigger())
    fireEvent.click(mediumTab('TV'))

    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))

    expect(onApply).toHaveBeenCalledWith([], 'TV')
  })

  it('discards an abandoned medium switch on reopen, same as an abandoned tick', () => {
    setupMedium('MOVIE', [18])
    fireEvent.click(mediumTrigger())
    fireEvent.click(mediumTab('TV'))
    expect(mediumTab('TV')).toHaveAttribute('aria-pressed', 'true')

    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(mediumTrigger())

    expect(mediumTab('Movies')).toHaveAttribute('aria-pressed', 'true')
    expect(box('Science Fiction')).toBeInTheDocument()
  })

  it('outside medium mode still calls onApply with exactly one argument (no medium)', () => {
    // Regression guard: an explicit `undefined` second argument is not the same
    // call as omitting it, and LibraryPage's onApply expects the latter.
    const { onApply } = setup([35, 18])
    fireEvent.click(trigger())

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    expect(onApply).toHaveBeenCalledWith([35, 18])
    expect(onApply.mock.calls[0]).toHaveLength(1)
  })
})
