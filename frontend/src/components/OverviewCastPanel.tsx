import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { CastMember } from '../types/api'

interface OverviewCastPanelProps {
  overview: string | null | undefined
  cast: CastMember[] | null | undefined
}

type Tab = 'overview' | 'cast'

// Stands in for a headshot when TMDB has no profile photo for a cast member.
export function PersonSilhouette() {
  return (
    <svg className="cast-photo-placeholder-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <circle cx="12" cy="8" r="4" fill="currentColor" />
      <path d="M4 21c0-4.42 3.58-7 8-7s8 2.58 8 7z" fill="currentColor" />
    </svg>
  )
}

// Overview and Cast share one panel behind a pill-tab toggle (#295). Titles
// TMDB has no credits for show the overview alone, with no empty Cast tab.
function OverviewCastPanel({ overview, cast }: OverviewCastPanelProps) {
  const hasOverview = Boolean(overview)
  const hasCast = Boolean(cast && cast.length > 0)
  const [tab, setTab] = useState<Tab>(hasOverview ? 'overview' : 'cast')

  if (!hasOverview && !hasCast) return null

  // A lone section keeps its plain heading — a one-option toggle isn't a choice.
  const showTabs = hasOverview && hasCast
  const activeTab: Tab = showTabs ? tab : hasOverview ? 'overview' : 'cast'

  return (
    <section className="panel">
      {showTabs ? (
        <div className="library-tabs overview-cast-tabs" role="tablist" aria-label="Title details">
          <button
            role="tab"
            aria-selected={activeTab === 'overview'}
            className={`library-tab${activeTab === 'overview' ? ' library-tab-active' : ''}`}
            onClick={() => setTab('overview')}
          >
            Overview
          </button>
          <button
            role="tab"
            aria-selected={activeTab === 'cast'}
            className={`library-tab${activeTab === 'cast' ? ' library-tab-active' : ''}`}
            onClick={() => setTab('cast')}
          >
            Cast
          </button>
        </div>
      ) : (
        <h3>{activeTab === 'overview' ? 'Overview' : 'Cast'}</h3>
      )}

      {activeTab === 'overview' ? (
        <p>{overview}</p>
      ) : (
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
    </section>
  )
}

export default OverviewCastPanel
