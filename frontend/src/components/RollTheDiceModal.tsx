import { useCallback, useEffect, useRef, useState } from 'react'
import { useApi } from '../contexts/AuthContext'
import type { TitleType, TonightPick, WatchlistEntryResponse } from '../types/api'

const MAX_PICKS = 6
export const MIN_PICKS = 2

// "What can I finish tonight?" (#359). The windows people actually think in: a sitcom,
// a drama episode, a short film, a normal film, a long one. Inclusive — a 90-minute
// film fits the 90m window.
//
// "Any" is a stop on the slider rather than a separate control (#366), and it is the
// *rightmost* one: the axis reads left-to-right as increasing time, so "no limit"
// belongs after 2h, not before 30m.
const TIME_STOPS: (number | null)[] = [30, 45, 60, 90, 120, null]
const ANY_INDEX = TIME_STOPS.length - 1

// The runtime label is wanted on every tile, including at "Any" where nothing is being
// filtered. `maxMinutes` is capped at 600 server-side, so asking for the ceiling means
// "everything with a known runtime" — one call at open populates the labels for the
// whole grid. It is a *label* source only: it never filters and never blocks, so a
// title the endpoint omits simply shows no label.
const ANY_MINUTES = 600

function windowLabel(minutes: number | null): string {
  if (minutes == null) return 'Any'
  return minutes === 120 ? '2h' : `${minutes}m`
}

function windowPhrase(minutes: number): string {
  return minutes === 120 ? '2 hours' : `${minutes} minutes`
}

// What a screen reader announces for the slider. Without it the raw index ("5") is
// read out — the tick captions below the track are decoration, not accessible values.
function stopValueText(index: number): string {
  const minutes = TIME_STOPS[index]
  return minutes == null ? 'Any time' : windowPhrase(minutes)
}

function pickLabel(pick: TonightPick): string {
  const runtime = `${pick.runtimeMinutes}m`
  return pick.nextSeason != null && pick.nextEpisode != null
    ? `S${pick.nextSeason} E${pick.nextEpisode} · ${runtime}`
    : runtime
}

// Which side the toggle opens on: Movies, unless Movies has too few to roll and TV
// doesn't — so the picker never opens on a grid you can't do anything with.
function defaultType(entries: WatchlistEntryResponse[]): TitleType {
  const movies = entries.filter(e => e.type === 'MOVIE').length
  const shows = entries.filter(e => e.type === 'TV').length
  return movies >= MIN_PICKS || shows < MIN_PICKS ? 'MOVIE' : 'TV'
}

interface RollTheDiceModalProps {
  entries: WatchlistEntryResponse[]
  watchlistId: number
  onClose: () => void
  onOpenEntry: (entry: WatchlistEntryResponse) => void
}

type Phase = 'select' | 'reveal'

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

function RollTheDiceModal({ entries, watchlistId, onClose, onOpenEntry }: RollTheDiceModalProps) {
  const api = useApi()
  const [phase, setPhase] = useState<Phase>('select')
  // Resolved once at mount: flipping the toggle has to stick, so this can't be derived
  // from `entries` on every render.
  const [mediaType, setMediaType] = useState<TitleType>(() => defaultType(entries))
  const [searchTerm, setSearchTerm] = useState('')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [displayId, setDisplayId] = useState<number | null>(null)
  const [chosenId, setChosenId] = useState<number | null>(null)
  const [isShuffling, setIsShuffling] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Time window (#359), now a slider position (#366). Each stop's answer is cached, so
  // dragging back and forth costs at most one request per stop.
  const [windowIndex, setWindowIndex] = useState(ANY_INDEX)
  const [picksByWindow, setPicksByWindow] = useState<Record<number, TonightPick[]>>({})
  // Which window is in flight / has failed, rather than bare booleans: a stop dragged
  // past must not inherit another one's spinner or its error.
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

  // Populate the runtime labels once at open. Keyed like any other stop, so selecting a
  // real one later reuses the cache machinery — and because nothing reads `pendingWindow`
  // or `failedWindow` while `timeWindow` is null, this can't show a spinner or an error.
  useEffect(() => { loadWindow(ANY_MINUTES) }, [loadWindow])

  function chooseStop(index: number) {
    setWindowIndex(index)
    // Selections made under the old stop may not survive the new one
    setSelected(new Set())
    const minutes = TIME_STOPS[index]
    if (minutes != null && !picksByWindow[minutes]) {
      loadWindow(minutes)
    }
  }

  function chooseType(type: TitleType) {
    if (type === mediaType) return
    setMediaType(type)
    // A selection made on one side has no meaning on the other
    setSelected(new Set())
    setSearchTerm('')
  }

  const timeWindow = TIME_STOPS[windowIndex]
  const activePicks = timeWindow != null ? picksByWindow[timeWindow] : undefined
  const isChecking = timeWindow != null && activePicks === undefined && pendingWindow === timeWindow
  const hasFailed = timeWindow != null && activePicks === undefined && failedWindow === timeWindow

  // Filtering and labelling are deliberately two different lookups. Only the active
  // stop decides what fits; labels fall back to the ceiling fetch so a tile still shows
  // its runtime at "Any" — and so a stop that is still loading can't filter the grid
  // through the ceiling's much wider set.
  const fitsIds = new Set((activePicks ?? []).map(pick => pick.entryId))
  const fitsWindow = (entry: WatchlistEntryResponse) =>
    timeWindow == null || fitsIds.has(entry.id)
  const labelPicks = activePicks ?? picksByWindow[ANY_MINUTES]
  const pickByEntryId = new Map((labelPicks ?? []).map(pick => [pick.entryId, pick]))

  const typeEntries = entries.filter(e => e.type === mediaType)
  const eligibleEntries = typeEntries.filter(fitsWindow)
  const visibleEntries = eligibleEntries.filter(e =>
    (e.name ?? '').toLowerCase().includes(searchTerm.trim().toLowerCase()),
  )

  // Counts on the toggle answer "what's behind the other side *right now*", so they
  // track the active stop rather than the raw list.
  const movieCount = entries.filter(e => e.type === 'MOVIE' && fitsWindow(e)).length
  const showCount = entries.filter(e => e.type === 'TV' && fitsWindow(e)).length

  // A stop narrow enough to leave one title makes the roll pointless — that title is
  // the answer, so present it instead of a dice you already know the result of.
  const solePick = timeWindow != null && eligibleEntries.length === 1 ? eligibleEntries[0] : null
  const nothingFits = timeWindow != null && eligibleEntries.length === 0
  const emptyType = typeEntries.length === 0

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else if (next.size < MAX_PICKS) next.add(id)
      return next
    })
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
    setPhase('select')
  }

  const byId = (id: number | null) => entries.find(e => e.id === id) ?? null
  const revealEntry = byId(isShuffling ? displayId : chosenId)
  const typeNoun = mediaType === 'MOVIE' ? 'movies' : 'shows'

  // The CTA carries the whole selection model: nothing selected rolls everything on
  // offer, one is short of a roll, two or more rolls just those.
  const rollCount = eligibleEntries.length
  const cta = selected.size === 0
    ? {
      label: `Roll All (${rollCount} Title${rollCount === 1 ? '' : 's'})`,
      disabled: rollCount < MIN_PICKS,
      onClick: rollAll,
    }
    : selected.size === 1
      ? { label: 'Select 1 more to roll…', disabled: true, onClick: () => {} }
      : {
        label: `Roll Selected (${selected.size})`,
        disabled: false,
        onClick: () => roll([...selected]),
      }

  // What replaces the grid when the toggle or the stop has something to say instead.
  // Order matters: an empty side of the toggle is not a window problem, so it is
  // reported before anything that talks about runtimes.
  function bodyState() {
    if (emptyType) {
      return <p className="flip-window-note">No {typeNoun} in this list.</p>
    }
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

  const body = phase === 'select' ? bodyState() : null

  return (
    <div className="flip-overlay" onClick={handleBackdropClick}>
      <div
        className={`flip-modal${phase === 'select' ? ' flip-modal-picker' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label="Roll the dice"
      >
        <button className="list-manage-close" onClick={onClose} aria-label="Close">×</button>

        {phase === 'select' && (
          <>
            <h3 className="flip-title">Roll the dice</h3>

            <div className="library-tabs flip-type-toggle" role="group" aria-label="Movies or TV">
              <button
                type="button"
                className={`library-tab${mediaType === 'MOVIE' ? ' library-tab-active' : ''}`}
                aria-pressed={mediaType === 'MOVIE'}
                onClick={() => chooseType('MOVIE')}
              >
                Movies ({movieCount})
              </button>
              <button
                type="button"
                className={`library-tab${mediaType === 'TV' ? ' library-tab-active' : ''}`}
                aria-pressed={mediaType === 'TV'}
                onClick={() => chooseType('TV')}
              >
                TV ({showCount})
              </button>
            </div>

            <div className="flip-slider">
              <input
                type="range"
                className="flip-slider-input"
                min={0}
                max={ANY_INDEX}
                step={1}
                value={windowIndex}
                aria-label="How much time do you have?"
                aria-valuetext={stopValueText(windowIndex)}
                onChange={e => chooseStop(Number(e.target.value))}
              />
              <div className="flip-slider-ticks" aria-hidden="true">
                {TIME_STOPS.map((minutes, i) => (
                  <span
                    key={windowLabel(minutes)}
                    className={`flip-slider-tick${i === windowIndex ? ' flip-slider-tick-active' : ''}`}
                  >
                    {windowLabel(minutes)}
                  </span>
                ))}
              </div>
            </div>

            {body ?? (
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
                  {/* Only once something is selected — otherwise the cap is noise */}
                  <span className="flip-count">
                    {selected.size > 0 ? `${selected.size} / ${MAX_PICKS} selected` : ''}
                  </span>
                  <button
                    className="flip-go-btn"
                    disabled={cta.disabled}
                    onClick={cta.onClick}
                  >
                    {cta.label}
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
