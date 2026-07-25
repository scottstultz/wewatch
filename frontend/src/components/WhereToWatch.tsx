import JustWatchAttribution from './JustWatchAttribution'
import type { WatchProvider } from '../types/api'

// The "Where to watch" panel, shared by TitleDetailPage and ShowDetailPage
// (#390). Presentational — the page owns the fetch and passes the providers
// straight off its `detail` state (same split as OverviewCastPanel, #358).
//
// The JustWatch attribution lives inside on purpose: TMDB's provider data is
// JustWatch-licensed and requires visible attribution wherever provider names
// or logos render (#270), so binding the two together means a future consumer
// cannot render one without the other.
//
// Note the data is flatrate (subscription) offers only, resolved for the
// caller's watch region — a rent/buy-only title legitimately has none, which
// is why empty renders nothing rather than an empty box.
function WhereToWatch({ providers }: { providers: WatchProvider[] | null }) {
  if (!providers || providers.length === 0) return null

  return (
    <section className="panel">
      <h3>Where to watch</h3>
      <div className="watch-provider-list">
        {providers.map(p => (
          <span key={p.id} className="watch-provider-item">
            {p.logoUrl && <img className="provider-badge-logo" src={p.logoUrl} alt="" loading="lazy" />}
            <span>{p.name}</span>
          </span>
        ))}
      </div>
      <JustWatchAttribution />
    </section>
  )
}

export default WhereToWatch
