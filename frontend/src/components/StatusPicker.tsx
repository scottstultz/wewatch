import type { WatchStatus } from '../types/api'

export const STATUS_LABELS: Record<WatchStatus, string> = {
  WANT_TO_WATCH: 'Want to Watch',
  WATCHING: 'Watching',
  WATCHED: 'Watched',
}

const OPTION_CLASSES: Record<WatchStatus, string> = {
  WANT_TO_WATCH: 'discover-status-option-want',
  WATCHING: 'discover-status-option-watching',
  WATCHED: 'discover-status-option-watched',
}

const STATUSES: WatchStatus[] = ['WANT_TO_WATCH', 'WATCHING', 'WATCHED']

interface StatusPickerProps {
  /** Currently assigned status, or null when the title isn't on the list yet. */
  current: WatchStatus | null
  disabled?: boolean
  onSelect: (status: WatchStatus) => void
}

function StatusPicker({ current, disabled, onSelect }: StatusPickerProps) {
  return (
    <div className="discover-status-picker" onClick={(e) => e.stopPropagation()}>
      {STATUSES.map(status => (
        <button
          key={status}
          className={`discover-status-option ${OPTION_CLASSES[status]}${current === status ? ' discover-status-option-current' : ''}`}
          disabled={disabled}
          onClick={(e) => { e.stopPropagation(); onSelect(status) }}
        >
          {current === status ? '✓ ' : ''}{STATUS_LABELS[status]}
        </button>
      ))}
    </div>
  )
}

export default StatusPicker
