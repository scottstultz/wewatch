import { useCallback, useEffect, useRef, useState } from 'react'
import type { Genre, TitleType } from '../types/api'

const MEDIUM_TABS: { value: TitleType; label: string }[] = [
  { value: 'MOVIE', label: 'Movies' },
  { value: 'TV', label: 'TV' },
]

interface GenreFilterProps {
  // Only the genres present in the current list, already labelled and name-sorted.
  // In medium mode this is the *committed* medium's catalog — the panel offers
  // mediumOptions[draftMedium] instead, which is what lets the switch swap the
  // checkboxes with no URL write.
  options: Genre[]
  // The committed selection, already intersected with `options` by the page.
  selected: number[]
  onApply: (genreIds: number[], medium?: TitleType) => void
  // ── Medium mode (#398) ──
  // Both optional so LibraryPage, which has no medium concept, is untouched: with
  // them absent this is exactly the #382 component, badge and all.
  medium?: TitleType
  mediumOptions?: Record<TitleType, Genre[]>
}

// A Genres ▾ trigger opening an anchored panel of checkboxes, committed with Apply (#382).
//
// Presentational on purpose — the page owns the catalog fetch and the committed selection
// (it lives in the URL), same split as OverviewCastPanel in #358. That is what lets this be
// tested in isolation without a router or a mocked ApiClient.
//
// ⚠️ Deferred commit is load-bearing, not cosmetic. Ticking a box must not filter the grid:
// only Apply and Clear call onApply. Here that avoids re-filtering per keystroke, and it is
// what lets #384 map one Apply to one TMDB request rather than one per checkbox — the same
// lesson as #368's settle timer. #398 puts the medium switch under the same rule for a
// stronger reason: with a selection already applied, committing on the toggle would re-fire
// browseByGenre on every click.
function GenreFilter({ options, selected, onApply, medium, mediumOptions }: GenreFilterProps) {
  const [isOpen, setIsOpen] = useState(false)
  // The uncommitted selection. Seeded from `selected` on open rather than in an effect, so
  // an abandoned panel can't leak its ticks into the next open.
  const [draft, setDraft] = useState<Set<number>>(new Set(selected))
  // Same reasoning for the medium: an abandoned switch must not survive a close.
  const [draftMedium, setDraftMedium] = useState<TitleType>(medium ?? 'MOVIE')
  // What the last medium switch dropped. Transient — it answers "where did Horror go?"
  // and has nothing to say once the user does anything else.
  const [notice, setNotice] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)

  // The medium switch shows only when the page supplies both a medium and the catalogs to
  // switch between — Discover does, the Library doesn't.
  const showMediums = medium !== undefined && mediumOptions !== undefined
  const shownOptions = showMediums ? mediumOptions[draftMedium] : options

  const handleToggle = useCallback(() => {
    setIsOpen(prev => {
      if (!prev) {
        setDraft(new Set(selected))
        setDraftMedium(medium ?? 'MOVIE')
        setNotice(null)
        setQuery('')
      }
      return !prev
    })
  }, [selected, medium])

  const handleToggleGenre = useCallback((id: number) => {
    setNotice(null)
    setDraft(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  // Switching medium keeps the drafted genres that exist in both catalogs and drops the
  // rest, matching **by id**. The near-miss pairs deliberately don't carry over — Science
  // Fiction (878) is not Sci-Fi & Fantasy (10765), and bridging them is an editorial
  // mapping TMDB doesn't publish (#398).
  const handleSelectMedium = useCallback((next: TitleType) => {
    if (!mediumOptions || next === draftMedium) return
    const nextIds = new Set(mediumOptions[next].map(g => g.id))
    const nameOf = new Map(mediumOptions[draftMedium].map(g => [g.id, g.name]))
    const kept: string[] = []
    const dropped: string[] = []
    for (const id of draft) {
      const name = nameOf.get(id) ?? String(id)
      if (nextIds.has(id)) kept.push(name)
      else dropped.push(name)
    }
    setDraft(prev => new Set([...prev].filter(id => nextIds.has(id))))
    setDraftMedium(next)
    setNotice(dropped.length === 0 ? null : dropNotice(kept, dropped, next))
  }, [draft, draftMedium, mediumOptions])

  // The medium argument is omitted entirely (not passed as `undefined`) outside
  // medium mode, so LibraryPage's onApply keeps the exact one-arg call it has always
  // made — a call with an explicit `undefined` second arg is not the same call.
  const handleApply = useCallback(() => {
    if (showMediums) onApply([...draft], draftMedium)
    else onApply([...draft])
    setIsOpen(false)
  }, [draft, draftMedium, showMediums, onApply])

  // Clear is a commit, not just a reset: it drops the filter in one click. The deferred
  // commit rule binds the checkboxes and the medium switch, not the footer.
  const handleClear = useCallback(() => {
    setDraft(new Set())
    if (showMediums) onApply([], draftMedium)
    else onApply([])
    setIsOpen(false)
  }, [draftMedium, showMediums, onApply])

  // Close on click outside
  useEffect(() => {
    if (!isOpen) return

    function handleMouseDown(e: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false)
      }
    }

    document.addEventListener('mousedown', handleMouseDown)
    return () => document.removeEventListener('mousedown', handleMouseDown)
  }, [isOpen])

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setIsOpen(false)
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isOpen])

  const trimmedQuery = query.trim().toLowerCase()
  const shown = trimmedQuery
    ? shownOptions.filter(g => g.name.toLowerCase().includes(trimmedQuery))
    : shownOptions

  // In medium mode the trigger is the single place the scope is stated, so it carries both
  // the medium and the count and the .nav-badge goes away. Without a medium there is no
  // scope to state and the badge stays (LibraryPage, #382).
  const triggerLabel = showMediums
    ? scopeLabel(selected, options, mediumLabel(medium))
    : 'Genres'

  return (
    <div className="genre-filter" ref={containerRef}>
      <button
        className="genre-filter-trigger"
        type="button"
        onClick={handleToggle}
        aria-expanded={isOpen}
        aria-haspopup="true"
      >
        {triggerLabel} <span aria-hidden="true">&#9662;</span>
        {!showMediums && selected.length > 0 && (
          <span className="nav-badge" aria-label={`${selected.length} selected`}>
            {selected.length}
          </span>
        )}
      </button>

      {isOpen && (
        <div className="genre-filter-panel">
          {showMediums && (
            <div className="library-tabs genre-filter-mediums" role="group" aria-label="Medium">
              {MEDIUM_TABS.map(tab => (
                <button
                  key={tab.value}
                  className={`library-tab${draftMedium === tab.value ? ' library-tab-active' : ''}`}
                  type="button"
                  onClick={() => handleSelectMedium(tab.value)}
                  aria-pressed={draftMedium === tab.value}
                >
                  {tab.label}
                </button>
              ))}
            </div>
          )}

          {notice && <p className="genre-filter-notice">{notice}</p>}

          <div className="search-input-wrapper genre-filter-search">
            <input
              className="search-input"
              type="search"
              placeholder="Find a genre…"
              aria-label="Find a genre"
              value={query}
              onChange={e => setQuery(e.target.value)}
            />
            {query && (
              <button
                className="search-clear-btn"
                type="button"
                onClick={() => setQuery('')}
                aria-label="Clear genre search"
              >
                ✕
              </button>
            )}
          </div>

          {shown.length > 0 ? (
            <div className="genre-filter-grid" role="group" aria-label="Genres">
              {shown.map(genre => (
                <label className="genre-filter-option" key={genre.id}>
                  <input
                    type="checkbox"
                    checked={draft.has(genre.id)}
                    onChange={() => handleToggleGenre(genre.id)}
                  />
                  <span>{genre.name}</span>
                </label>
              ))}
            </div>
          ) : (
            <p className="search-status">No genres match "{query.trim()}".</p>
          )}

          <div className="genre-filter-footer">
            <button className="genre-filter-clear" type="button" onClick={handleClear}>
              Clear
            </button>
            <button className="genre-filter-apply" type="button" onClick={handleApply}>
              Apply
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function mediumLabel(medium: TitleType | undefined): string {
  return medium === 'TV' ? 'TV' : 'Movies'
}

// `Genres` · `Movies • Comedy` · `TV • 3 Genres` (#398). One genre is named because the
// name is shorter and more useful than the count; past that the names stop fitting on a
// trigger, so the count carries it.
function scopeLabel(selected: number[], options: Genre[], medium: string): string {
  if (selected.length === 0) return 'Genres'
  if (selected.length === 1) {
    const name = options.find(g => g.id === selected[0])?.name
    if (name) return `${medium} • ${name}`
  }
  return `${medium} • ${selected.length} Genres`
}

// "Kept Comedy · Romance and Horror aren't TV genres"
function dropNotice(kept: string[], dropped: string[], medium: TitleType): string {
  const noun = medium === 'TV' ? 'TV' : 'movie'
  const lost = dropped.length === 1
    ? `${dropped[0]} isn’t a ${noun} genre`
    : `${joinNames(dropped)} aren’t ${noun} genres`
  return kept.length > 0 ? `Kept ${joinNames(kept)} · ${lost}` : lost
}

function joinNames(names: string[]): string {
  if (names.length < 2) return names.join('')
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

export default GenreFilter
