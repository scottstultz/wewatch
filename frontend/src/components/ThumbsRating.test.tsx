import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import ThumbsRating from './ThumbsRating'

describe('ThumbsRating', () => {
  it('rates up when unrated', () => {
    const onRate = vi.fn()
    render(<ThumbsRating rating={null} onRate={onRate} />)

    fireEvent.click(screen.getByRole('button', { name: 'Thumbs up — more like this' }))

    expect(onRate).toHaveBeenCalledWith('UP')
  })

  it('clears the rating when tapping the active thumb', () => {
    const onRate = vi.fn()
    render(<ThumbsRating rating="UP" onRate={onRate} />)

    fireEvent.click(screen.getByRole('button', { name: 'Remove thumbs up' }))

    expect(onRate).toHaveBeenCalledWith(null)
  })

  it('switches straight from up to down', () => {
    const onRate = vi.fn()
    render(<ThumbsRating rating="UP" onRate={onRate} />)

    fireEvent.click(screen.getByRole('button', { name: 'Thumbs down — less like this' }))

    expect(onRate).toHaveBeenCalledWith('DOWN')
  })

  it('reflects the rating in aria-pressed', () => {
    render(<ThumbsRating rating="DOWN" onRate={() => {}} />)

    expect(screen.getByRole('button', { name: /Thumbs up/ })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: /thumbs down/i })).toHaveAttribute('aria-pressed', 'true')
  })

  it('ignores clicks while disabled', () => {
    const onRate = vi.fn()
    render(<ThumbsRating rating={null} onRate={onRate} disabled />)

    fireEvent.click(screen.getByRole('button', { name: 'Thumbs up — more like this' }))
    fireEvent.click(screen.getByRole('button', { name: 'Thumbs down — less like this' }))

    expect(onRate).not.toHaveBeenCalled()
  })
})
