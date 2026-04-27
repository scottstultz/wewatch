function DiscoverPage() {
  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Discover</p>
          <h2>Placeholder search and recommendations view.</h2>
          <p>
            This route is reserved for title discovery, search results, and
            browse workflows.
          </p>
        </div>
      </section>

      <section className="stack-list">
        <article className="panel">
          <h3>Search results</h3>
          <p>Future title cards, filters, and provider badges will live here.</p>
        </article>
        <article className="panel">
          <h3>Trending picks</h3>
          <p>Room for personalized or editorial recommendations.</p>
        </article>
      </section>
    </div>
  )
}

export default DiscoverPage
