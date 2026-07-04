import { useCallback, useEffect, useRef, useState } from 'react'
import type { TitleType, WatchlistEntryResponse } from '../types/api'

const MAX_PICKS = 6
export const MIN_PICKS = 2

interface RollTheDiceModalProps {
  entries: WatchlistEntryResponse[]
  wantToWatchMode?: boolean
  onClose: () => void
  onOpenEntry: (entry: WatchlistEntryResponse) => void
}

type Phase = 'type' | 'mode' | 'select' | 'reveal'

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function RollTheDiceModal({ entries, wantToWatchMode, onClose, onOpenEntry }: RollTheDiceModalProps) {
  const [phase, setPhase] = useState<Phase>(wantToWatchMode ? 'type' : 'select')
  const [mediaType, setMediaType] = useState<TitleType | null>(null)
  const [searchTerm, setSearchTerm] = useState('')
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

  const eligibleEntries = wantToWatchMode
    ? entries.filter(e => e.type === mediaType)
    : entries
  const visibleEntries = eligibleEntries.filter(e =>
    (e.name ?? '').toLowerCase().includes(searchTerm.trim().toLowerCase()),
  )

  const movieCount = entries.filter(e => e.type === 'MOVIE').length
  const showCount = entries.filter(e => e.type === 'TV').length

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else if (next.size < MAX_PICKS) next.add(id)
      return next
    })
  }

  function chooseType(type: TitleType) {
    setMediaType(type)
    setSelected(new Set())
    setSearchTerm('')
    setPhase('mode')
  }

  function roll(ids: number[]) {
    if (ids.length < MIN_PICKS) return
    const chosen = ids[Math.floor(Math.random() * ids.length)]
    setChosenId(chosen)
    setPhase('reveal')

    if (prefersReducedMotion()) {
      setDisplayId(chosen)
      setIsShuffling(false)
      return
    }

    // Cycle through the picks, decelerating, then land on the winner.
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

  function rollAll() {
    const ids = eligibleEntries.map(e => e.id)
    if (ids.length < MIN_PICKS) return
    setSelected(new Set(ids))
    roll(ids)
  }

  function rollAgain() {
    if (timerRef.current) clearTimeout(timerRef.current)
    setChosenId(null)
    setDisplayId(null)
    setIsShuffling(false)
    setSelected(new Set())
    setSearchTerm('')
    setPhase(wantToWatchMode ? 'mode' : 'select')
  }

  const byId = (id: number | null) => entries.find(e => e.id === id) ?? null
  const revealEntry = byId(isShuffling ? displayId : chosenId)

  const typeLabel = mediaType === 'MOVIE' ? 'Movies' : 'Shows'

  return (
    <div className="flip-overlay" onClick={handleBackdropClick}>
      <div className="flip-modal" role="dialog" aria-modal="true" aria-label="Roll the dice">
        <button className="list-manage-close" onClick={onClose} aria-label="Close">×</button>

        {phase === 'type' && (
          <>
            <h3 className="flip-title">Roll the dice</h3>
            <p className="flip-subtitle">What do you want to roll for?</p>

            <div className="flip-mode-actions">
              <button
                className="flip-go-btn"
                disabled={movieCount < MIN_PICKS}
                onClick={() => chooseType('MOVIE')}
              >
                Movies ({movieCount})
              </button>
              <button
                className="flip-go-btn"
                disabled={showCount < MIN_PICKS}
                onClick={() => chooseType('TV')}
              >
                Shows ({showCount})
              </button>
            </div>
          </>
        )}

        {phase === 'mode' && (
          <>
            <button className="flip-back-btn" onClick={() => setPhase('type')}>‹ Back</button>
            <h3 className="flip-title">Roll the dice</h3>
            <p className="flip-subtitle">
              {typeLabel} in Want to Watch — how do you want to roll?
            </p>

            <div className="flip-mode-actions">
              <button
                className="flip-go-btn"
                disabled={eligibleEntries.length < MIN_PICKS}
                onClick={rollAll}
              >
                Roll all ({eligibleEntries.length})
              </button>
              <button
                className="flip-secondary-btn"
                disabled={eligibleEntries.length < MIN_PICKS}
                onClick={() => { setSelected(new Set()); setPhase('select') }}
              >
                Narrow it down
              </button>
            </div>
          </>
        )}

        {phase === 'select' && (
          <>
            {wantToWatchMode && (
              <button className="flip-back-btn" onClick={() => setPhase('mode')}>‹ Back</button>
            )}
            <h3 className="flip-title">Roll the dice</h3>
            <p className="flip-subtitle">
              Pick {MIN_PICKS}–{MAX_PICKS}{wantToWatchMode ? ` ${typeLabel.toLowerCase()}` : " of the shows you're watching"} and let fate choose.
            </p>

            <div className="search-input-wrapper">
              <input
                className="search-input"
                type="search"
                placeholder="Search titles…"
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
              />
              {searchTerm && (
                <button
                  className="search-clear-btn"
                  onClick={() => setSearchTerm('')}
                  aria-label="Clear search"
                >
                  ✕
                </button>
              )}
            </div>

            <div className="flip-select-grid">
              {visibleEntries.map(entry => {
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
                onClick={() => roll([...selected])}
              >
                Roll!
              </button>
            </div>
          </>
        )}

        {phase === 'reveal' && (
          <div className="flip-reveal">
            <h3 className="flip-title">{isShuffling ? 'Rolling…' : 'You should watch'}</h3>

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
                {revealEntry?.type && (
                  <button className="flip-go-btn" onClick={() => onOpenEntry(revealEntry)}>
                    Open
                  </button>
                )}
                <button className="flip-secondary-btn" onClick={rollAgain}>Roll again</button>
                <button className="flip-secondary-btn" onClick={onClose}>Done</button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default RollTheDiceModal
