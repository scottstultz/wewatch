import { useEffect, useMemo, useState } from 'react'
import { useApi } from '../contexts/AuthContext'
import JustWatchAttribution from '../components/JustWatchAttribution'
import type { WatchProvider, WatchRegion } from '../types/api'

// The full regional catalog runs to well over a hundred services; the picker
// shows the most popular slice (plus anything already selected) unless expanded.
const COLLAPSED_PROVIDER_COUNT = 24

type SaveState = 'idle' | 'saving' | 'saved' | 'error'

function ProfilePage() {
  const api = useApi()
  const [userId, setUserId] = useState<number | null>(null)
  const [regions, setRegions] = useState<WatchRegion[]>([])
  const [providers, setProviders] = useState<WatchProvider[]>([])
  const [region, setRegion] = useState<string>('')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveState, setSaveState] = useState<SaveState>('idle')
  const [showAll, setShowAll] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')

  // Load current settings and the region list once
  useEffect(() => {
    let cancelled = false
    Promise.all([api.getMe(), api.getWatchRegions()])
      .then(([user, regionList]) => {
        if (cancelled) return
        setUserId(user.id)
        setRegion(user.watchRegion ?? '')
        setSelected(new Set(user.watchProviderIds ?? []))
        setRegions(regionList)
      })
      .catch(() => {
        if (!cancelled) setLoadError('Failed to load streaming settings.')
      })
    return () => { cancelled = true }
  }, [api])

  // The provider catalog is region-scoped
  useEffect(() => {
    if (!region) {
      setProviders([])
      return
    }
    let cancelled = false
    api.getWatchProviders(region)
      .then(list => { if (!cancelled) setProviders(list) })
      .catch(() => { if (!cancelled) setLoadError('Failed to load streaming services.') })
    return () => { cancelled = true }
  }, [api, region])

  const filteredProviders = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    return q ? providers.filter(p => p.name.toLowerCase().includes(q)) : providers
  }, [providers, searchQuery])

  const visibleProviders = useMemo(() => {
    if (searchQuery.trim()) return filteredProviders
    return showAll
      ? providers
      : providers.filter((p, i) => i < COLLAPSED_PROVIDER_COUNT || selected.has(p.id))
  }, [providers, filteredProviders, showAll, selected, searchQuery])

  function toggleProvider(id: number) {
    setSaveState('idle')
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function handleSave() {
    if (userId == null || !region) return
    setSaveState('saving')
    try {
      await api.updateStreamingSettings(userId, region, [...selected])
      setSaveState('saved')
    } catch {
      setSaveState('error')
    }
  }

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Profile</p>
          <h2>Your streaming services.</h2>
          <p>
            Tell WeWatch what you subscribe to and suggestions will favor titles
            you can actually press play on, with availability badges on tiles.
          </p>
        </div>
      </section>

      <section className="stack-list">
        <article className="panel">
          <h3>Streaming services</h3>
          {loadError && <p className="search-status search-status-error">{loadError}</p>}

          <div className="settings-field-row">
            <label className="discover-picker-label" htmlFor="watch-region">Country:</label>
            <select
              id="watch-region"
              className="discover-picker-select"
              value={region}
              onChange={e => { setSaveState('idle'); setShowAll(false); setSearchQuery(''); setRegion(e.target.value) }}
            >
              <option value="">Select a country…</option>
              {regions.map(r => (
                <option key={r.code} value={r.code}>{r.name}</option>
              ))}
            </select>
          </div>

          {region && providers.length > 0 && (
            <>
              {providers.length > COLLAPSED_PROVIDER_COUNT && (
                <div className="search-input-wrapper provider-search-row">
                  <input
                    className="search-input"
                    type="search"
                    placeholder="Search streaming services…"
                    aria-label="Search streaming services"
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                  />
                  {searchQuery && (
                    <button
                      className="search-clear-btn"
                      onClick={() => setSearchQuery('')}
                      aria-label="Clear search"
                    >
                      ✕
                    </button>
                  )}
                </div>
              )}

              {filteredProviders.length > 0 ? (
                <div className="provider-grid" role="group" aria-label="Streaming services">
                  {visibleProviders.map(p => (
                    <button
                      key={p.id}
                      type="button"
                      className={`provider-chip${selected.has(p.id) ? ' provider-chip-selected' : ''}`}
                      aria-pressed={selected.has(p.id)}
                      onClick={() => toggleProvider(p.id)}
                    >
                      {p.logoUrl && <img className="provider-chip-logo" src={p.logoUrl} alt="" loading="lazy" />}
                      <span className="provider-chip-name">{p.name}</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="search-status">No streaming services match "{searchQuery.trim()}".</p>
              )}
              {!showAll && !searchQuery.trim() && providers.length > COLLAPSED_PROVIDER_COUNT && (
                <button className="provider-show-all-btn" onClick={() => setShowAll(true)}>
                  Show all {providers.length} services
                </button>
              )}

              <div className="settings-save-row">
                <button
                  className="settings-save-btn"
                  disabled={saveState === 'saving'}
                  onClick={handleSave}
                >
                  {saveState === 'saving' ? 'Saving…' : 'Save'}
                </button>
                {saveState === 'saved' && <span className="settings-save-note">Saved</span>}
                {saveState === 'error' && (
                  <span className="settings-save-note discover-error-label">Failed to save. Try again.</span>
                )}
              </div>
            </>
          )}

          <JustWatchAttribution />
        </article>
      </section>
    </div>
  )
}

export default ProfilePage
