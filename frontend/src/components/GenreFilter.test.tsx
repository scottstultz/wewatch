import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import GenreFilter from './GenreFilter'
import type { Genre } from '../types/api'

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
