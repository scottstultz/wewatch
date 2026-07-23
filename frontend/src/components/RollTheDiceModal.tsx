import { useCallback, useEffect, useRef, useState } from 'react'
import { useApi } from '../contexts/AuthContext'
import type { TitleType, TonightPick, WatchlistEntryResponse } from '../types/api'

const MAX_PICKS = 6
export const MIN_PICKS = 2

// "What can I finish tonight?" (#359). The windows people actually think in: a sitcom,
// a drama episode, a short film, a normal film, a long one. Inclusive — a 90-minute
// film fits the 90m window.
const TIME_WINDOWS = [30, 45, 60, 90, 120]

function windowLabel(minutes: number): string {
  return minutes === 120 ? '2h' : `${minutes}m`
}

function windowPhrase(minutes: number): string {
  return minutes === 120 ? '2 hours' : `${minutes} minutes`
}

function pickLabel(pick: TonightPick): string {
  const runtime = `${pick.runtimeMinutes}m`
  return pick.nextSeason != null && pick.nextEpisode != null
    ? `S${pick.nextSeason} E${pick.nextEpisode} · ${runtime}`
    : runtime
}

interface RollTheDiceModalProps {
  entries: WatchlistEntryResponse[]
  watchlistId: number
  wantToWatchMode?: boolean
  onClose: () => void
  onOpenEntry: (entry: WatchlistEntryResponse) => void
}

type Phase = 'type' | 'mode' | 'select' | 'reveal'

// Module scope on purpose: Math.random is impure, and the lint rules that keep render
// pure flag it inside the component even where it is only ever reached from a handler.
function randomOf(ids: number[]): number {
  return ids[Math.floor(Math.random() * ids.length)]
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function RevealCard({ entry, showBody }: { entry: WatchlistEntryResponse; showBody: boolean }) {
  return (
    <>
      {entry.posterUrl ? (
        <img className="flip-reveal-poster" src={entry.posterUrl} alt={entry.name ?? ''} />
      ) : (
        <div className="flip-reveal-poster flip-tile-poster-empty">{entry.name}</div>
      )}
      {showBody && (
        <div className="flip-reveal-body">
          <span className="title-type-badge">{entry.type === 'MOVIE' ? 'Movie' : 'TV Show'}</span>
          <p className="flip-reveal-name">{entry.name}</p>
        </div>
      )}
    </>
  )
}

function RollTheDiceModal({
  entries, watchlistId, wantToWatchMode, onClose, onOpenEntry,
}: RollTheDiceModalProps) {
  const api = useApi()
  const [phase, setPhase] = useState<Phase>(wantToWatchMode ? 'type' : 'select')
  const [mediaType, setMediaType] = useState<TitleType | null>(null)
  const [searchTerm, setSearchTerm] = useState('')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [displayId, setDisplayId] = useState<number | null>(null)
  const [chosenId, setChosenId] = useState<number | null>(null)
  const [isShuffling, setIsShuffling] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Time window (#359). null = "Any time". Each window's answer is cached, so moving
  // back and forth across the chips costs at most one request per window.
  const [timeWindow, setTimeWindow] = useState<number | null>(null)
  const [picksByWindow, setPicksByWindow] = useState<Record<number, TonightPick[]>>({})
  // Which window is in flight / has failed, rather than bare booleans: a second chip
  // tapped mid-request must not inherit the first one's spinner or its error.
  const [pendingWindow, setPendingWindow] = useState<number | null>(null)
  const [failedWindow, setFailedWindow] = useState<number | null>(null)

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

  const loadWindow = useCallback((minutes: number) => {
    setPendingWindow(minutes)
    setFailedWindow(prev => (prev === minutes ? null : prev))
    api.getTonightPicks(watchlistId, minutes)
      .then(picks => setPicksByWindow(prev => ({ ...prev, [minutes]: picks })))
      .catch(() => setFailedWindow(minutes))
      .finally(() => setPendingWindow(prev => (prev === minutes ? null : prev)))
  }, [api, watchlistId])

  function chooseWindow(minutes: number | null) {
    setTimeWindow(minutes)
    // Selections made under the old window may not survive the new one
    setSelected(new Set())
    if (minutes != null && !picksByWindow[minutes]) {
      loadWindow(minutes)
    }
  }

  const activePicks = timeWindow != null ? picksByWindow[timeWindow] : undefined
  const pickByEntryId = new Map((activePicks ?? []).map(pick => [pick.entryId, pick]))
  const isChecking = timeWindow != null && activePicks === undefined && pendingWindow === timeWindow
  const hasFailed = timeWindow != null && activePicks === undefined && failedWindow === timeWindow
  const fitsWindow = (entry: WatchlistEntryResponse) =>
    timeWindow == null || pickByEntryId.has(entry.id)

  const eligibleEntries = (wantToWatchMode ? entries.filter(e => e.type === mediaType) : entries)
    .filter(fitsWindow)
  const visibleEntries = eligibleEntries.filter(e =>
    (e.name ?? '').toLowerCase().includes(searchTerm.trim().toLowerCase()),
  )

  const movieCount = entries.filter(e => e.type === 'MOVIE' && fitsWindow(e)).length
  const showCount = entries.filter(e => e.type === 'TV' && fitsWindow(e)).length

  // A window narrow enough to leave one title makes the roll pointless — that title is
  // the answer, so present it instead of a dice you already know the result of.
  const solePick = timeWindow != null && eligibleEntries.length === 1 ? eligibleEntries[0] : null
  const nothingFits = timeWindow != null
    && (phase === 'type' ? movieCount + showCount === 0 : eligibleEntries.length === 0)

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
    const chosen = randomOf(ids)
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
  // The minimum to roll: with a window on, one fitting title is handled by solePick, so
  // anything that reaches a roll button genuinely needs two.
  const enoughToRoll = (count: number) => count >= MIN_PICKS

  const windowChips = (
    <div className="flip-window-row" role="group" aria-label="How much time do you have?">
      <button
        type="button"
        className={`flip-window-chip${timeWindow == null ? ' flip-window-chip-active' : ''}`}
        aria-pressed={timeWindow == null}
        onClick={() => chooseWindow(null)}
      >
        Any time
      </button>
      {TIME_WINDOWS.map(minutes => (
        <button
          key={minutes}
          type="button"
          className={`flip-window-chip${timeWindow === minutes ? ' flip-window-chip-active' : ''}`}
          aria-pressed={timeWindow === minutes}
          onClick={() => chooseWindow(minutes)}
        >
          {windowLabel(minutes)}
        </button>
      ))}
    </div>
  )

  // What replaces a phase's body when the window has something to say about it.
  function windowState() {
    if (hasFailed) {
      return (
        <div className="flip-window-note">
          <p>Couldn't check runtimes just now.</p>
          <button className="flip-secondary-btn" onClick={() => loadWindow(timeWindow!)}>
            Try again
          </button>
        </div>
      )
    }
    if (isChecking) {
      return <p className="flip-window-note">Checking runtimes…</p>
    }
    if (nothingFits) {
      return (
        <p className="flip-window-note">
          Nothing here fits in {windowPhrase(timeWindow!)}. Try a longer window.
        </p>
      )
    }
    if (solePick) {
      const pick = pickByEntryId.get(solePick.id)
      return (
        <div className="flip-reveal">
          <p className="flip-subtitle">
            Only one thing fits {windowPhrase(timeWindow!)} — so that's the pick.
          </p>
          <div className="flip-reveal-card flip-reveal-landed">
            <RevealCard entry={solePick} showBody />
          </div>
          {pick && <p className="flip-count">{pickLabel(pick)}</p>}
          <div className="flip-reveal-actions">
            <button className="flip-go-btn" onClick={() => onOpenEntry(solePick)}>Open</button>
          </div>
        </div>
      )
    }
    return null
  }

  const windowBody = phase === 'reveal' ? null : windowState()

  return (
    <div className="flip-overlay" onClick={handleBackdropClick}>
      <div className="flip-modal" role="dialog" aria-modal="true" aria-label="Roll the dice">
        <button className="list-manage-close" onClick={onClose} aria-label="Close">×</button>

        {phase === 'type' && (
          <>
            <h3 className="flip-title">Roll the dice</h3>
            <p className="flip-subtitle">What do you want to roll for?</p>

            {windowChips}

            {windowBody ?? (
              <div className="flip-mode-actions">
                <button
                  className="flip-go-btn"
                  disabled={movieCount === 0 || (timeWindow == null && !enoughToRoll(movieCount))}
                  onClick={() => chooseType('MOVIE')}
                >
                  Movies ({movieCount})
                </button>
                <button
                  className="flip-go-btn"
                  disabled={showCount === 0 || (timeWindow == null && !enoughToRoll(showCount))}
                  onClick={() => chooseType('TV')}
                >
                  Shows ({showCount})
                </button>
              </div>
            )}
          </>
        )}

        {phase === 'mode' && (
          <>
            <button className="flip-back-btn" onClick={() => setPhase('type')}>‹ Back</button>
            <h3 className="flip-title">Roll the dice</h3>
            <p className="flip-subtitle">
              {typeLabel} in Want to Watch — how do you want to roll?
            </p>

            {windowChips}

            {windowBody ?? (
              <div className="flip-mode-actions">
                <button
                  className="flip-go-btn"
                  disabled={!enoughToRoll(eligibleEntries.length)}
                  onClick={rollAll}
                >
                  Roll all ({eligibleEntries.length})
                </button>
                <button
                  className="flip-secondary-btn"
                  disabled={!enoughToRoll(eligibleEntries.length)}
                  onClick={() => { setSelected(new Set()); setPhase('select') }}
                >
                  Narrow it down
                </button>
              </div>
            )}
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

            {windowChips}

            {windowBody ?? (
              <>
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
                    const pick = pickByEntryId.get(entry.id)
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
                        {pick && <span className="flip-tile-runtime">{pickLabel(pick)}</span>}
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
                <RevealCard entry={revealEntry} showBody={!isShuffling} />
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
