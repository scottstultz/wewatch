import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import { fireEvent, render as rtlRender, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import OverviewCastPanel from './OverviewCastPanel'
import type { CastMember } from '../types/api'

const CAST: CastMember[] = [
  { id: 1, name: 'Keanu Reeves', character: 'Neo', profileUrl: 'https://img/neo.jpg' },
  { id: 3, name: 'Joe Pantoliano', character: 'Cypher', profileUrl: null },
]

// Cast tiles are react-router Links (#305), so the panel needs a router.
function render(ui: ReactElement) {
  return rtlRender(<MemoryRouter>{ui}</MemoryRouter>)
}

describe('OverviewCastPanel', () => {
  it('opens on Overview and switches to Cast when the tab is clicked', () => {
    render(<OverviewCastPanel overview="A hacker learns the truth." cast={CAST} />)

    expect(screen.getByText('A hacker learns the truth.')).toBeInTheDocument()
    expect(screen.queryByText('Keanu Reeves')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Cast' }))

    expect(screen.getByText('Keanu Reeves')).toBeInTheDocument()
    expect(screen.getByText('Neo')).toBeInTheDocument()
    expect(screen.queryByText('A hacker learns the truth.')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Cast' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'false')
  })

  it('switches back to Overview', () => {
    render(<OverviewCastPanel overview="A hacker learns the truth." cast={CAST} />)

    fireEvent.click(screen.getByRole('tab', { name: 'Cast' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))

    expect(screen.getByText('A hacker learns the truth.')).toBeInTheDocument()
  })

  it('renders a photo for cast with a profile and a placeholder for those without', () => {
    render(<OverviewCastPanel overview="Synopsis." cast={CAST} />)
    fireEvent.click(screen.getByRole('tab', { name: 'Cast' }))

    // Photos are decorative (alt="") — the name renders right beside them, so
    // they carry no accessible name to query by
    const photos = document.querySelectorAll('img.cast-photo')
    expect(photos).toHaveLength(1)
    expect(photos[0]).toHaveAttribute('src', 'https://img/neo.jpg')
    expect(document.querySelectorAll('.cast-photo-placeholder')).toHaveLength(1)
  })

  it('shows Overview with no tabs when there is no cast', () => {
    render(<OverviewCastPanel overview="Synopsis." cast={[]} />)

    expect(screen.getByText('Synopsis.')).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
  })

  it('shows Cast with no tabs when there is no overview', () => {
    render(<OverviewCastPanel overview={null} cast={CAST} />)

    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Cast' })).toBeInTheDocument()
    expect(screen.getByText('Keanu Reeves')).toBeInTheDocument()
  })

  it('renders nothing when there is neither an overview nor cast', () => {
    const { container } = render(<OverviewCastPanel overview={null} cast={null} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('links each cast tile to the person page (#305)', () => {
    render(<OverviewCastPanel overview={null} cast={CAST} />)

    expect(screen.getByRole('link', { name: /Keanu Reeves/ })).toHaveAttribute('href', '/person/1')
    expect(screen.getByRole('link', { name: /Joe Pantoliano/ })).toHaveAttribute('href', '/person/3')
  })

  it('omits the character line when TMDB has no role for a credit', () => {
    render(
      <OverviewCastPanel
        overview={null}
        cast={[{ id: 9, name: 'Uncredited Extra', character: null, profileUrl: null }]}
      />,
    )

    expect(screen.getByText('Uncredited Extra')).toBeInTheDocument()
    expect(document.querySelector('.cast-character')).toBeNull()
  })
})
