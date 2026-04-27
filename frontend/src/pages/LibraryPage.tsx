function LibraryPage() {
  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Library</p>
          <h2>Placeholder tracking view for saved titles.</h2>
          <p>
            This route will become the main status-based library for watchlist,
            in-progress, and watched items.
          </p>
        </div>
      </section>

      <section className="content-grid">
        <article className="panel">
          <h3>Want to watch</h3>
          <p>Queued titles and quick actions belong here.</p>
        </article>
        <article className="panel">
          <h3>Watching</h3>
          <p>Active titles and progress controls belong here.</p>
        </article>
        <article className="panel">
          <h3>Watched</h3>
          <p>Completed titles, notes, and ratings belong here.</p>
        </article>
      </section>
    </div>
  )
}

export default LibraryPage
