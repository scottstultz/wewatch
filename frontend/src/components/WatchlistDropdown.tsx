import { useCallback, useEffect, useRef, useState } from 'react'
import type { WatchlistResponse } from '../types/api'

interface WatchlistDropdownProps {
  watchlists: WatchlistResponse[]
  selectedWatchlistId: number | null
  onSelect: (id: number) => void
  onCreateNew: () => void
}

function WatchlistDropdown({
  watchlists,
  selectedWatchlistId,
  onSelect,
  onCreateNew,
}: WatchlistDropdownProps) {
  const [isOpen, setIsOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const selectedName =
    watchlists.find(w => w.id === selectedWatchlistId)?.name ?? 'Select list'

  const handleToggle = useCallback(() => {
    setIsOpen(prev => !prev)
  }, [])

  const handleSelect = useCallback(
    (id: number) => {
      onSelect(id)
      setIsOpen(false)
    },
    [onSelect],
  )

  const handleCreateNew = useCallback(() => {
    onCreateNew()
    setIsOpen(false)
  }, [onCreateNew])

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

  return (
    <div className="watchlist-dropdown" ref={containerRef}>
      <button
        className="watchlist-dropdown-trigger"
        onClick={handleToggle}
        aria-expanded={isOpen}
        aria-haspopup="listbox"
      >
        {selectedName} <span aria-hidden="true">&#9662;</span>
      </button>

      {isOpen && (
        <ul className="watchlist-dropdown-menu" role="listbox">
          {watchlists.map(wl => (
            <li key={wl.id} role="option" aria-selected={wl.id === selectedWatchlistId}>
              <button
                className={`watchlist-dropdown-item${wl.id === selectedWatchlistId ? ' watchlist-dropdown-item-active' : ''}`}
                onClick={() => handleSelect(wl.id)}
              >
                <span>{wl.name}</span>
                {wl.isDefault && (
                  <span className="watchlist-default-badge">Default</span>
                )}
                {wl.id === selectedWatchlistId && (
                  <span className="watchlist-check" aria-hidden="true">&#10003;</span>
                )}
              </button>
            </li>
          ))}
          <li className="watchlist-dropdown-divider" role="separator" />
          <li>
            <button
              className="watchlist-dropdown-item watchlist-dropdown-item-new"
              onClick={handleCreateNew}
            >
              + New list
            </button>
          </li>
        </ul>
      )}
    </div>
  )
}

export default WatchlistDropdown
