import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { CastMember } from '../types/api'

interface OverviewCastPanelProps {
  overview: string | null | undefined
  cast: CastMember[] | null | undefined
  // "More Like This" (#358) rides as an optional third tab. The parent renders
  // the body (a grid of recommendation tiles) and passes it in, so the panel
  // stays presentational and knows nothing about the API/watchlist wiring.
  moreLikeThis?: ReactNode
  // Fired the first time the More Like This tab becomes active — the parent uses
  // it to lazily fetch recommendations only when the user opens the tab.
  onShowMoreLikeThis?: () => void
}

type Tab = 'overview' | 'cast' | 'moreLikeThis'

const TAB_LABELS: Record<Tab, string> = {
  overview: 'Overview',
  cast: 'Cast',
  moreLikeThis: 'More Like This',
}

// Stands in for a headshot when TMDB has no profile photo for a cast member.
export function PersonSilhouette() {
  return (
    <svg className="cast-photo-placeholder-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <circle cx="12" cy="8" r="4" fill="currentColor" />
      <path d="M4 21c0-4.42 3.58-7 8-7s8 2.58 8 7z" fill="currentColor" />
    </svg>
  )
}

// Overview, Cast, and (optionally) More Like This share one panel behind a
// pill-tab toggle (#295, #358). Only the sections that have content get a tab;
// a lone available section keeps its plain heading — a one-option toggle isn't
// a choice.
function OverviewCastPanel({ overview, cast, moreLikeThis, onShowMoreLikeThis }: OverviewCastPanelProps) {
  const hasOverview = Boolean(overview)
  const hasCast = Boolean(cast && cast.length > 0)
  const hasMoreLikeThis = moreLikeThis != null

  const available: Tab[] = []
  if (hasOverview) available.push('overview')
  if (hasCast) available.push('cast')
  if (hasMoreLikeThis) available.push('moreLikeThis')

  const [tab, setTab] = useState<Tab>(available[0] ?? 'overview')

  const showTabs = available.length >= 2
  const activeTab: Tab = showTabs ? tab : (available[0] ?? 'overview')

  // Lazy trigger (#358): fetch recommendations only once the tab is actually
  // viewed — whether selected here or shown as the lone available section.
  useEffect(() => {
    if (activeTab === 'moreLikeThis') onShowMoreLikeThis?.()
  }, [activeTab, onShowMoreLikeThis])

  if (available.length === 0) return null

  return (
    <section className="panel">
      {showTabs ? (
        <div className="library-tabs overview-cast-tabs" role="tablist" aria-label="Title details">
          {available.map(t => (
            <button
              key={t}
              role="tab"
              aria-selected={activeTab === t}
              className={`library-tab${activeTab === t ? ' library-tab-active' : ''}`}
              onClick={() => setTab(t)}
            >
              {TAB_LABELS[t]}
            </button>
          ))}
        </div>
      ) : (
        <h3>{TAB_LABELS[activeTab]}</h3>
      )}

      {activeTab === 'overview' && <p>{overview}</p>}

      {activeTab === 'cast' && (
        <ul className="cast-grid">
          {cast!.map(member => (
            <li key={member.id}>
              <Link className="cast-card" to={`/person/${member.id}`}>
                {member.profileUrl ? (
                  <img className="cast-photo" src={member.profileUrl} alt="" loading="lazy" />
                ) : (
                  <div className="cast-photo cast-photo-placeholder">
                    <PersonSilhouette />
                  </div>
                )}
                <span className="cast-name">{member.name}</span>
                {member.character && <span className="cast-character">{member.character}</span>}
              </Link>
            </li>
          ))}
        </ul>
      )}

      {activeTab === 'moreLikeThis' && moreLikeThis}
    </section>
  )
}

export default OverviewCastPanel
