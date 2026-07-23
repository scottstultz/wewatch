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

// How long the slider must hold still before its stop is fetched (#368). A range input
// fires onChange for every discrete step the thumb crosses, so a drag across the track
// used to fire one request — and one loading state — per stop. Only the *fetch* waits;
// `windowIndex` still updates on every event so the thumb never lags the pointer.
const SETTLE_MS = 150

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
  // Which window failed, rather than a bare boolean: a stop dragged past must not
  // inherit another one's error. (#366 tracked the in-flight window here too; since
  // #368 "in flight" is just "the settled stop has no answer yet", which also covers
  // the settle delay before the request is even made.)
  const [failedWindow, setFailedWindow] = useState<number | null>(null)
  // The stop the grid is actually filtered by (#368). It deliberately lags `windowIndex`:
  // through the settle delay and the request that follows, the grid keeps rendering the
  // last stop that resolved instead of emptying out. Without it the grid would drop to
  // zero tiles on every drag — which is the height collapse this issue is about, and also
  // what made `nothingFits` trivially true the moment the old "Checking runtimes…" branch
  // stopped guarding it.
  const [appliedIndex, setAppliedIndex] = useState(ANY_INDEX)
  const appliedRef = useRef(ANY_INDEX)

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
    setFailedWindow(prev => (prev === minutes ? null : prev))
    api.getTonightPicks(watchlistId, minutes)
      .then(picks => setPicksByWindow(prev => ({ ...prev, [minutes]: picks })))
      .catch(() => setFailedWindow(minutes))
  }, [api, watchlistId])

  // Populate the runtime labels once at open. Keyed like any other stop, so selecting a
  // real one later reuses the cache machinery — and because nothing reads `pendingWindow`
  // or `failedWindow` while `timeWindow` is null, this can't show a spinner or an error.
  useEffect(() => { loadWindow(ANY_MINUTES) }, [loadWindow])

  const timeWindow = TIME_STOPS[windowIndex]

  // Fetch the stop the slider *settled* on, not every stop the thumb crossed (#368). The
  // cleanup cancels the previous stop's timer, so one drag across the track schedules five
  // and fires one. Re-running when `picksByWindow` fills only ever makes the cache check
  // below return earlier, at the cost of restarting one timer.
  useEffect(() => {
    if (timeWindow == null || picksByWindow[timeWindow]) return
    const timer = setTimeout(() => loadWindow(timeWindow), SETTLE_MS)
    return () => clearTimeout(timer)
  }, [timeWindow, picksByWindow, loadWindow])

  // Advance the grid onto the current stop once there is an answer to show for it.
  useEffect(() => {
    if (timeWindow != null && picksByWindow[timeWindow] === undefined) return
    if (appliedRef.current !== windowIndex) {
      appliedRef.current = windowIndex
      // A stop that genuinely changed invalidates selections made under the old one.
      // Dragging away and back settles on the same stop, so it leaves them alone.
      setSelected(new Set())
    }
    setAppliedIndex(windowIndex)
  }, [windowIndex, timeWindow, picksByWindow])

  function chooseType(type: TitleType) {
    if (type === mediaType) return
    setMediaType(type)
    // A selection made on one side has no meaning on the other
    setSelected(new Set())
    setSearchTerm('')
  }

  const activePicks = timeWindow != null ? picksByWindow[timeWindow] : undefined
  const hasFailed = timeWindow != null && activePicks === undefined && failedWindow === timeWindow
  // "The settled stop has no answer yet" — which spans the settle delay as well as the
  // request itself (#368), so the grid dims once for the whole gap instead of flickering
  // at the boundary between them. `pendingWindow` is now only the retry button's business.
  const isChecking = timeWindow != null && activePicks === undefined && !hasFailed

  // What the grid renders against: the applied stop, which lags the slider (#368).
  const appliedWindow = TIME_STOPS[appliedIndex]
  const appliedPicks = appliedWindow != null ? picksByWindow[appliedWindow] : undefined

  // Filtering and labelling are deliberately two different lookups. Only the applied
  // stop decides what fits; labels fall back to the ceiling fetch so a tile still shows
  // its runtime at "Any" — and so a stop that is still loading can't filter the grid
  // through the ceiling's much wider set.
  const fitsIds = new Set((appliedPicks ?? []).map(pick => pick.entryId))
  const fitsWindow = (entry: WatchlistEntryResponse) =>
    appliedWindow == null || fitsIds.has(entry.id)
  const labelPicks = appliedPicks ?? picksByWindow[ANY_MINUTES]
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

  // Both of these swap the body out, so they may only describe the stop the grid is
  // actually showing (#368) — otherwise a drag past a narrow stop would collapse the
  // modal mid-gesture and quote a duration the user has already moved off.
  const settled = appliedIndex === windowIndex

  // A stop narrow enough to leave one title makes the roll pointless — that title is
  // the answer, so present it instead of a dice you already know the result of.
  const solePick = settled && appliedWindow != null && eligibleEntries.length === 1
    ? eligibleEntries[0]
    : null
  const nothingFits = settled && appliedWindow != null && eligibleEntries.length === 0
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
  //
  // `isChecking` is folded into each branch rather than OR'd at the JSX (#368) — the
  // tiles on screen belong to the previous stop while a check is in flight, so rolling
  // them would pick from a set that is about to change. Written this way on purpose:
  // `disabled={cta.disabled || isChecking}` at the call site is a react-hooks/refs
  // *error*, not a warning, and CI fails on errors. Neither operand alone trips it.
  const rollCount = eligibleEntries.length
  const cta = selected.size === 0
    ? {
      label: `Roll All (${rollCount} Title${rollCount === 1 ? '' : 's'})`,
      disabled: rollCount < MIN_PICKS || isChecking,
      onClick: rollAll,
    }
    : selected.size === 1
      ? { label: 'Select 1 more to roll…', disabled: true, onClick: () => {} }
      : {
        label: `Roll Selected (${selected.size})`,
        disabled: isChecking,
        onClick: () => roll([...selected]),
      }

  // What replaces the grid when the toggle or the stop has something to say instead.
  // Order matters: an empty side of the toggle is not a window problem, so it is
  // reported before anything that talks about runtimes.
  //
  // Every state here is a *settled* one. An in-flight check deliberately isn't — it dims
  // the grid in place rather than swapping it out (#368), because swapping is what made
  // the modal collapse and re-expand on every stop the thumb crossed.
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
                // Moves the thumb and nothing else — the fetch and the selection reset
                // both wait for the drag to settle (#368).
                onChange={e => setWindowIndex(Number(e.target.value))}
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

                <div className={`flip-select-grid${isChecking ? ' flip-select-grid-checking' : ''}`}>
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
                        // These tiles are the *previous* stop's set while a check is in
                        // flight and some of them won't survive the new one, so they must
                        // not be selectable. `disabled` rather than the stylesheet's
                        // pointer-events alone: that leaves them keyboard-reachable (#368).
                        disabled={atCap || isChecking}
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
                  {/* Only once something is selected — otherwise the cap is noise. The
                      in-flight message rides this slot on purpose (#368): it is already
                      rendered unconditionally, so borrowing it costs no height, and a
                      check has cleared the selection it would otherwise displace. */}
                  <span className="flip-count" aria-live="polite">
                    {isChecking
                      ? 'Checking runtimes…'
                      : selected.size > 0 ? `${selected.size} / ${MAX_PICKS} selected` : ''}
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
