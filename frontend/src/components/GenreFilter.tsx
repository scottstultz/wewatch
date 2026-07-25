import { useCallback, useEffect, useRef, useState } from 'react'
import type { Genre } from '../types/api'

interface GenreFilterProps {
  // Only the genres present in the current list, already labelled and name-sorted.
  options: Genre[]
  // The committed selection, already intersected with `options` by the page.
  selected: number[]
  onApply: (genreIds: number[]) => void
}

// A Genres ▾ trigger opening an anchored panel of checkboxes, committed with Apply (#382).
//
// Presentational on purpose — the page owns the catalog fetch and the committed selection
// (it lives in the URL), same split as OverviewCastPanel in #358. That is what lets this be
// tested in isolation without a router or a mocked ApiClient.
//
// ⚠️ Deferred commit is load-bearing, not cosmetic. Ticking a box must not filter the grid:
// only Apply and Clear call onApply. Here that avoids re-filtering per keystroke, and it is
// what will let #384 map one Apply to one TMDB request rather than one per checkbox — the
// same lesson as #368's settle timer.
function GenreFilter({ options, selected, onApply }: GenreFilterProps) {
  const [isOpen, setIsOpen] = useState(false)
  // The uncommitted selection. Seeded from `selected` on open rather than in an effect, so
  // an abandoned panel can't leak its ticks into the next open.
  const [draft, setDraft] = useState<Set<number>>(new Set(selected))
  const [query, setQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)

  const handleToggle = useCallback(() => {
    setIsOpen(prev => {
      if (!prev) {
        setDraft(new Set(selected))
        setQuery('')
      }
      return !prev
    })
  }, [selected])

  const handleToggleGenre = useCallback((id: number) => {
    setDraft(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const handleApply = useCallback(() => {
    onApply([...draft])
    setIsOpen(false)
  }, [draft, onApply])

  // Clear is a commit, not just a reset: it drops the filter in one click. The deferred
  // commit rule binds the checkboxes, not the footer.
  const handleClear = useCallback(() => {
    setDraft(new Set())
    onApply([])
    setIsOpen(false)
  }, [onApply])

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
    ? options.filter(g => g.name.toLowerCase().includes(trimmedQuery))
    : options

  return (
    <div className="genre-filter" ref={containerRef}>
      <button
        className="genre-filter-trigger"
        type="button"
        onClick={handleToggle}
        aria-expanded={isOpen}
        aria-haspopup="true"
      >
        Genres <span aria-hidden="true">&#9662;</span>
        {selected.length > 0 && (
          <span className="nav-badge" aria-label={`${selected.length} selected`}>
            {selected.length}
          </span>
        )}
      </button>

      {isOpen && (
        <div className="genre-filter-panel">
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

export default GenreFilter
