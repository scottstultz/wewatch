import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useApi } from '../contexts/AuthContext'
import { useWatchlists } from '../contexts/WatchlistContext'
import { PersonSilhouette } from '../components/OverviewCastPanel'
import TitleCard, { cardKey } from '../components/TitleCard'
import { useTitleCardActions } from '../hooks/useTitleCardActions'
import type { PersonDetailResponse, TitleSearchResponse } from '../types/api'

type CreditFilter = 'all' | 'MOVIE' | 'TV'
type CreditOrder = 'popularity' | 'newest'

const FILTER_LABELS: Record<CreditFilter, string> = {
  all: 'All',
  MOVIE: 'Movies',
  TV: 'TV',
}

const ORDER_LABELS: Record<CreditOrder, string> = {
  popularity: 'Popularity',
  newest: 'Newest',
}

function yearOf(dateStr: string | null): string | null {
  if (!dateStr) return null
  const d = new Date(dateStr)
  return Number.isNaN(d.getTime()) ? null : String(d.getFullYear())
}

function timeOf(dateStr: string | null): number | null {
  if (!dateStr) return null
  const t = new Date(dateStr).getTime()
  return Number.isNaN(t) ? null : t
}

// Newest first, undated credits last — TMDB ships those for announced but
// unreleased projects, and sorting them as epoch 0 would read as a person's
// oldest work. Ties (and two undated credits) compare equal, so the stable sort
// leaves them in the popularity order the mapper delivered.
function byNewest(a: TitleSearchResponse, b: TitleSearchResponse): number {
  const at = timeOf(a.releaseDate)
  const bt = timeOf(b.releaseDate)
  if (at === null && bt === null) return 0
  if (at === null) return 1
  if (bt === null) return -1
  return bt - at
}

// Reached from a cast tile on title details (#305) — a person's bio plus their
// acting credits across movies and TV, with Discover's add-to-watchlist tiles.
function PersonPage() {
  const { personId } = useParams<{ personId: string }>()
  const location = useLocation()
  const navigate = useNavigate()
  const api = useApi()
  const { watchlists, selectedWatchlistId, selectWatchlist, isLoading: watchlistsLoading } = useWatchlists()

  const [person, setPerson] = useState<PersonDetailResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<CreditFilter>('all')
  // Popularity is the order the mapper delivers, and stays the default — it
  // leads with the work a person is known for (#402).
  const [order, setOrder] = useState<CreditOrder>('popularity')
  // The tiles stay hidden until the watchlist has been reconciled once, so an
  // add can never be clobbered by a reconcile that was still in flight.
  const [seeded, setSeeded] = useState(false)
  const {
    cardStatus,
    setCardStatus,
    setEntryIds,
    pickingKey,
    handleAddToWatchlist,
    togglePicker,
    handleChangeStatus,
    handleRemove,
  } = useTitleCardActions(api, selectedWatchlistId)

  useEffect(() => {
    const id = Number(personId)
    if (!Number.isInteger(id) || id <= 0) {
      setError('Unknown person.')
      setIsLoading(false)
      return
    }
    let cancelled = false
    setIsLoading(true)
    setError(null)

    api.getPerson(id)
      .then(data => {
        if (cancelled) return
        setPerson(data)
        setIsLoading(false)
      })
      .catch(() => {
        if (cancelled) return
        setError('Failed to load person details.')
        setIsLoading(false)
      })

    return () => { cancelled = true }
  }, [api, personId])

  // Which credits are already on the watchlist — the same reconciliation the
  // Discover search does. This waits for the watchlists to settle: seeding
  // against a not-yet-chosen list would wipe the statuses right after painting
  // them, and a reconcile still in flight would clobber an optimistic add.
  useEffect(() => {
    if (!person || watchlistsLoading) return
    if (!selectedWatchlistId) {
      setSeeded(true)
      return
    }
    let cancelled = false

    api.getWatchlistEntries(selectedWatchlistId)
      .then(entries => {
        if (cancelled) return
        const entryByKey = new Map(entries.map(e => [`${e.externalSource}-${e.externalId}`, e]))
        setCardStatus(prev => {
          const next = { ...prev }
          person.credits.forEach(credit => {
            const k = cardKey(credit)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.status
            else if (next[k] !== 'loading') delete next[k]
          })
          return next
        })
        setEntryIds(prev => {
          const next = { ...prev }
          person.credits.forEach(credit => {
            const k = cardKey(credit)
            const existing = entryByKey.get(k)
            if (existing) next[k] = existing.id
            else delete next[k]
          })
          return next
        })
      })
      .catch(() => { /* statuses stay unknown — the tiles still add */ })
      .finally(() => {
        if (!cancelled) setSeeded(true)
      })

    return () => { cancelled = true }
  }, [api, person, selectedWatchlistId, watchlistsLoading, setCardStatus, setEntryIds])

  function openTitle(title: TitleSearchResponse) {
    navigate(
      `/title/${title.type.toLowerCase()}/${title.externalSource}/${title.externalId}`,
      { state: { title } },
    )
  }

  function goBack() {
    // location.key is 'default' when this page is the first in-app history
    // entry (opened directly) — there is nothing to go back to (#241).
    if (location.key === 'default') navigate('/discover')
    else navigate(-1)
  }

  if (error) {
    return (
      <div className="page">
        <p className="search-status search-status-error">{error}</p>
        <button className="show-detail-back-btn" onClick={goBack}>
          &#8592; Back
        </button>
      </div>
    )
  }

  const credits = person?.credits ?? []
  const filteredCredits = filter === 'all' ? credits : credits.filter(c => c.type === filter)
  // Sorting a copy: `credits` is the response object the reconcile effects read.
  // Nothing is sliced here — both orders show the same credits, so a person at
  // the mapper's 100-credit cap keeps all 100 either way.
  const shownCredits = order === 'newest' ? [...filteredCredits].sort(byNewest) : filteredCredits
  const birthYear = yearOf(person?.birthday ?? null)

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="show-detail-header">
          <button className="show-detail-back-btn" onClick={goBack} aria-label="Back">
            &#8592; Back
          </button>

          {person && (
            <div className="show-detail-hero">
              {person.profileUrl ? (
                <img className="show-detail-poster" src={person.profileUrl} alt={person.name} />
              ) : (
                <div className="show-detail-poster cast-photo-placeholder">
                  <PersonSilhouette />
                </div>
              )}
              <div className="show-detail-info">
                <h2 className="show-detail-title">{person.name}</h2>

                <div className="title-detail-meta">
                  {person.knownForDepartment && <span>{person.knownForDepartment}</span>}
                  {birthYear && <span>Born {birthYear}</span>}
                  {person.placeOfBirth && <span>{person.placeOfBirth}</span>}
                </div>

                {person.biography && <p className="person-bio">{person.biography}</p>}

                {watchlists.length > 1 && (
                  <div className="discover-watchlist-picker">
                    <span className="discover-picker-label">Adding to:</span>
                    <select
                      className="discover-picker-select"
                      value={selectedWatchlistId ?? ''}
                      onChange={e => selectWatchlist(Number(e.target.value))}
                    >
                      {watchlists.map(wl => (
                        <option key={wl.id} value={wl.id}>{wl.name}</option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </section>

      {(isLoading || (person && !seeded)) && <p className="search-status">Loading…</p>}

      {seeded && credits.length === 0 && (
        <p className="search-status">No credits to show for {person?.name}.</p>
      )}

      {seeded && credits.length > 0 && (
        <section className="stack-list">
          <div className="person-filmography-controls">
            <div className="library-tabs" role="tablist" aria-label="Filmography filter">
              {(['all', 'MOVIE', 'TV'] as CreditFilter[]).map(value => (
                <button
                  key={value}
                  role="tab"
                  aria-selected={filter === value}
                  className={`library-tab${filter === value ? ' library-tab-active' : ''}`}
                  onClick={() => setFilter(value)}
                >
                  {FILTER_LABELS[value]}
                </button>
              ))}
            </div>

            {/* A two-state switch, not a second tablist — these pick the order of
                one grid rather than swapping panels (the #366 toggle's idiom). */}
            <div className="library-tabs" role="group" aria-label="Filmography order">
              {(['popularity', 'newest'] as CreditOrder[]).map(value => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={order === value}
                  className={`library-tab${order === value ? ' library-tab-active' : ''}`}
                  onClick={() => setOrder(value)}
                >
                  {ORDER_LABELS[value]}
                </button>
              ))}
            </div>
          </div>

          <div className="title-grid">
            {shownCredits.map(credit => (
              <TitleCard
                key={cardKey(credit)}
                title={credit}
                status={cardStatus[cardKey(credit)] ?? 'idle'}
                isPicking={pickingKey === cardKey(credit)}
                onAdd={handleAddToWatchlist}
                onChangeStatus={handleChangeStatus}
                onTogglePicker={togglePicker}
                onOpen={openTitle}
                onRemove={handleRemove}
              />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

export default PersonPage
