import type { TitleRating } from '../types/api'

interface ThumbsRatingProps {
  rating: TitleRating | null
  // Called with the next rating; null means "clear" (tapping the active thumb)
  onRate: (rating: TitleRating | null) => void
  disabled?: boolean
}

// Thumbs up/down rating control (#273). Personal to the signed-in user —
// rendered on Library cards and title detail headers, never on suggestion
// tiles (rate what you've engaged with, dismiss what you haven't).
function ThumbsRating({ rating, onRate, disabled }: ThumbsRatingProps) {
  return (
    <div className="thumbs-rating" role="group" aria-label="Rate this title">
      <button
        className={`thumb-btn${rating === 'UP' ? ' thumb-btn-up-active' : ''}`}
        disabled={disabled}
        onClick={() => onRate(rating === 'UP' ? null : 'UP')}
        aria-pressed={rating === 'UP'}
        aria-label={rating === 'UP' ? 'Remove thumbs up' : 'Thumbs up — more like this'}
        title="More like this"
      >
        👍
      </button>
      <button
        className={`thumb-btn${rating === 'DOWN' ? ' thumb-btn-down-active' : ''}`}
        disabled={disabled}
        onClick={() => onRate(rating === 'DOWN' ? null : 'DOWN')}
        aria-pressed={rating === 'DOWN'}
        aria-label={rating === 'DOWN' ? 'Remove thumbs down' : 'Thumbs down — less like this'}
        title="Less like this"
      >
        👎
      </button>
    </div>
  )
}

export default ThumbsRating
