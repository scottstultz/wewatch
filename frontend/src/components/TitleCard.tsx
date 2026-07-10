import StatusPicker, { STATUS_LABELS } from './StatusPicker'
import type { TitleSearchResponse, WatchProvider, WatchStatus } from '../types/api'

export type AddHandler = (title: TitleSearchResponse, status: WatchStatus) => void
export type OpenHandler = (title: TitleSearchResponse) => void
export type ToggleHandler = (title: TitleSearchResponse) => void
export type RemoveHandler = (title: TitleSearchResponse) => void
export type DismissHandler = (title: TitleSearchResponse) => void

export type CardStatus = 'idle' | 'loading' | 'error' | WatchStatus

export function cardKey(title: TitleSearchResponse) {
  return `${title.externalSource}-${title.externalId}`
}

interface TitleCardProps {
  title: TitleSearchResponse
  status: CardStatus
  isPicking: boolean
  onAdd: AddHandler
  onChangeStatus: AddHandler
  onTogglePicker: ToggleHandler
  onOpen: OpenHandler
  onRemove: RemoveHandler
  // Only suggestion tiles get the "Not interested" affordance (#268); search
  // results have no dismiss concept, so the prop is absent there
  onDismiss?: DismissHandler
  // id -> provider lookup for availability badges (#270); absent on search
  // results and when the user has no streaming services configured
  providersById?: Map<number, WatchProvider>
}

function TitleCard({ title, status, isPicking, onAdd, onChangeStatus, onTogglePicker, onOpen, onRemove, onDismiss, providersById }: TitleCardProps) {
  const addedStatus =
    status === 'WANT_TO_WATCH' || status === 'WATCHING' || status === 'WATCHED' ? status : null
  const badgeProviders = (providersById && title.providerIds ? title.providerIds : [])
    .map(id => providersById?.get(id))
    .filter((p): p is WatchProvider => p != null)
    .slice(0, 3)
  return (
    <article
      className="title-card title-card-clickable"
      role="button"
      tabIndex={0}
      onClick={() => onOpen(title)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen(title)
        }
      }}
      aria-label={`View details for ${title.name}`}
    >
      {onDismiss && (
        <button
          className="title-dismiss-btn"
          onClick={(e) => { e.stopPropagation(); onDismiss(title) }}
          aria-label={`Not interested in ${title.name}`}
          title="Not interested"
        >
          ✕
        </button>
      )}
      {title.posterUrl ? (
        <img className="title-poster" src={title.posterUrl} alt={title.name} loading="lazy" />
      ) : (
        <div className="title-poster title-poster-empty" />
      )}
      {badgeProviders.length > 0 && (
        <div className="provider-badge-row" aria-label="Streaming on your services">
          {badgeProviders.map(p => (
            p.logoUrl && <img key={p.id} className="provider-badge-logo" src={p.logoUrl} alt={p.name} title={p.name} loading="lazy" />
          ))}
        </div>
      )}
      <div className="title-card-body">
        <span className="title-type-badge">
          {title.type === 'MOVIE' ? 'Movie' : 'TV Show'}
        </span>
        <p className="title-name">{title.name}</p>
        {title.releaseDate && (
          <p className="title-year">{new Date(title.releaseDate).getFullYear()}</p>
        )}
        {addedStatus ? (
          isPicking ? (
            <StatusPicker
              current={addedStatus}
              onSelect={(s) => onChangeStatus(title, s)}
              onRemove={() => onRemove(title)}
            />
          ) : (
            <div className="discover-action-row">
              <button
                className="discover-added-chip"
                onClick={(e) => { e.stopPropagation(); onTogglePicker(title) }}
                aria-label={`Status: ${STATUS_LABELS[addedStatus]}. Tap to change.`}
              >
                <span className="discover-round-btn discover-round-btn-added" aria-hidden="true">✓</span>
                <span className="discover-added-label">{STATUS_LABELS[addedStatus]}</span>
              </button>
            </div>
          )
        ) : (
          <div className="discover-action-row">
            <button
              className={`discover-round-btn discover-round-btn-add${status === 'error' ? ' discover-round-btn-error' : ''}`}
              disabled={status === 'loading'}
              onClick={(e) => { e.stopPropagation(); onAdd(title, 'WANT_TO_WATCH') }}
              aria-label={status === 'error' ? 'Retry adding to watchlist' : 'Add to watchlist'}
            >
              {status === 'loading' ? '…' : (
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                  <path d="M7 1v12M1 7h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
                </svg>
              )}
            </button>
            {status === 'error' && (
              <span className="discover-added-label discover-error-label">Failed — tap to retry</span>
            )}
          </div>
        )}
      </div>
    </article>
  )
}

export default TitleCard
