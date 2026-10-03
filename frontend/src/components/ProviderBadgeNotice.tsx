import { useState } from 'react'
import { Link } from 'react-router-dom'

// #419: make the provider-availability badges (#270/#392) legible even when
// they're switched off. A blank tile is ambiguous — badges disabled, title
// uncached, or genuinely not on your services all look identical — so these two
// quiet, honest cues remove the ambiguity. Presentation only; nothing here may
// break the page.

// Shown when provider context is active (providersById !== null): a one-time,
// secondary-text line clarifying what a logo means and that a blank tile is
// information, not absence. Deliberately honest about partial coverage — a blank
// tile is never proof a title is unavailable everywhere.
export function ProviderBadgeHint() {
  return (
    <p className="provider-badge-hint">
      Logos show titles on your streaming services — a blank tile just means we
      don’t know it’s on yours.
    </p>
  )
}

// Dismissal persists across reloads (localStorage); the AC allows session-only,
// so this is a strict superset. One shared key, so dismissing on Discover also
// dismisses on Library — "the feature exists on Profile" is a single fact.
const DISMISS_KEY = 'wewatch:provider-pointer-dismissed'

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISS_KEY) === '1'
  } catch {
    // Private mode / blocked storage — treat as not dismissed, show the pointer
    return false
  }
}

// Shown only when the user has no streaming services configured
// (servicesConfigured === false — never while the profile is still loading, and
// never on a fetch error, both of which stay silent). Points at where to turn
// the feature on. Dismissible, and the caller never renders it once services are
// configured.
export function ProviderSetupPointer() {
  const [dismissed, setDismissed] = useState(readDismissed)

  if (dismissed) return null

  function handleDismiss() {
    setDismissed(true)
    try {
      localStorage.setItem(DISMISS_KEY, '1')
    } catch {
      // Storage unavailable — the pointer still hides for this render; it may
      // reappear on reload, which is acceptable for a decorative hint
    }
  }

  return (
    <p className="provider-setup-pointer" role="note">
      <span>
        Set your streaming services on{' '}
        <Link to="/profile">Profile</Link> to see where titles are streaming.
      </span>
      <button
        type="button"
        className="provider-setup-pointer-dismiss"
        onClick={handleDismiss}
        aria-label="Dismiss"
      >
        ✕
      </button>
    </p>
  )
}
