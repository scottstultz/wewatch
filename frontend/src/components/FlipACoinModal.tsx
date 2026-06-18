import { useCallback, useEffect, useRef, useState } from 'react'
import type { WatchlistEntryResponse } from '../types/api'

const MAX_PICKS = 5
const MIN_PICKS = 2

interface FlipACoinModalProps {
  entries: WatchlistEntryResponse[]
  onClose: () => void
  onOpenEntry: (entry: WatchlistEntryResponse) => void
}

type Phase = 'select' | 'reveal'

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function FlipACoinModal({ entries, onClose, onOpenEntry }: FlipACoinModalProps) {
  const [phase, setPhase] = useState<Phase>('select')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [displayId, setDisplayId] = useState<number | null>(null)
  const [chosenId, setChosenId] = useState<number | null>(null)
  const [isShuffling, setIsShuffling] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Close on Escape
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  // Clear any pending shuffle timer on unmount
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current) }, [])

  const handleBackdropClick = useCallback(
    (e: React.MouseEvent) => { if (e.target === e.currentTarget) onClose() },
    [onClose],
  )

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else if (next.size < MAX_PICKS) next.add(id)
      return next
    })
  }

  function flip() {
    const ids = [...selected]
    if (ids.length < MIN_PICKS) return
    const chosen = ids[Math.floor(Math.random() * ids.length)]
    setChosenId(chosen)
    setPhase('reveal')

    if (prefersReducedMotion()) {
      setDisplayId(chosen)
      setIsShuffling(false)
      return
    }

    // Shuffle: cycle through the picks, decelerating, then land on the winner.
    setIsShuffling(true)
    let i = 0
    let delay = 70
    const tick = () => {
      setDisplayId(ids[i % ids.length])
      i++
      delay *= 1.14
      if (delay < 340) {
        timerRef.current = setTimeout(tick, delay)
      } else {
        setDisplayId(chosen)
        setIsShuffling(false)
      }
    }
    tick()
  }

  function flipAgain() {
    if (timerRef.current) clearTimeout(timerRef.current)
    setChosenId(null)
    setDisplayId(null)
    setIsShuffling(false)
    setPhase('select')
  }

  const byId = (id: number | null) => entries.find(e => e.id === id) ?? null
  const revealEntry = byId(isShuffling ? displayId : chosenId)

  return (
    <div className="flip-overlay" onClick={handleBackdropClick}>
      <div className="flip-modal" role="dialog" aria-modal="true" aria-label="Flip a coin">
        <button className="list-manage-close" onClick={onClose} aria-label="Close">×</button>

        {phase === 'select' ? (
          <>
            <h3 className="flip-title">Can't decide? Flip a coin</h3>
            <p className="flip-subtitle">
              Pick {MIN_PICKS}–{MAX_PICKS} of the shows you're watching and let fate choose.
            </p>

            <div className="flip-select-grid">
              {entries.map(entry => {
                const isSelected = selected.has(entry.id)
                const atCap = selected.size >= MAX_PICKS && !isSelected
                return (
                  <button
                    key={entry.id}
                    type="button"
                    className={`flip-tile${isSelected ? ' flip-tile-selected' : ''}`}
                    aria-pressed={isSelected}
                    disabled={atCap}
                    onClick={() => toggle(entry.id)}
                    title={entry.name ?? undefined}
                  >
                    {entry.posterUrl ? (
                      <img className="flip-tile-poster" src={entry.posterUrl} alt={entry.name ?? ''} loading="lazy" />
                    ) : (
                      <div className="flip-tile-poster flip-tile-poster-empty">{entry.name}</div>
                    )}
                    {isSelected && <span className="flip-tile-check" aria-hidden="true">✓</span>}
                  </button>
                )
              })}
            </div>

            <div className="flip-actions">
              <span className="flip-count">{selected.size} / {MAX_PICKS} selected</span>
              <button
                className="flip-go-btn"
                disabled={selected.size < MIN_PICKS}
                onClick={flip}
              >
                Flip!
              </button>
            </div>
          </>
        ) : (
          <div className="flip-reveal">
            <h3 className="flip-title">{isShuffling ? 'Flipping…' : 'You should watch'}</h3>

            {revealEntry && (
              <div
                key={revealEntry.id}
                className={`flip-reveal-card${isShuffling ? ' flip-reveal-shuffling' : ' flip-reveal-landed'}`}
              >
                {revealEntry.posterUrl ? (
                  <img className="flip-reveal-poster" src={revealEntry.posterUrl} alt={revealEntry.name ?? ''} />
                ) : (
                  <div className="flip-reveal-poster flip-tile-poster-empty">{revealEntry.name}</div>
                )}
                {!isShuffling && (
                  <div className="flip-reveal-body">
                    <span className="title-type-badge">
                      {revealEntry.type === 'MOVIE' ? 'Movie' : 'TV Show'}
                    </span>
                    <p className="flip-reveal-name">{revealEntry.name}</p>
                  </div>
                )}
              </div>
            )}

            {!isShuffling && (
              <div className="flip-reveal-actions">
                {revealEntry?.type === 'TV' && (
                  <button className="flip-go-btn" onClick={() => onOpenEntry(revealEntry)}>
                    Open
                  </button>
                )}
                <button className="flip-secondary-btn" onClick={flipAgain}>Flip again</button>
                <button className="flip-secondary-btn" onClick={onClose}>Done</button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default FlipACoinModal
