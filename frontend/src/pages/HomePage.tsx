function HomePage() {
  return (
    <div className="page">
      <section className="hero-panel">
        <div className="hero-copy">
          <p className="section-kicker">Tonight</p>
          <h2>Keep the next watch decision quick.</h2>
          <p>
            This shell is designed around phone-sized screens first, with space
            for discovery, tracking, and library workflows to expand cleanly.
          </p>
        </div>
        <div className="stats-grid">
          <article className="stat-card">
            <span className="stat-label">Want to watch</span>
            <strong className="stat-value">24</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Watching</span>
            <strong className="stat-value">3</strong>
          </article>
          <article className="stat-card">
            <span className="stat-label">Watched</span>
            <strong className="stat-value">118</strong>
          </article>
        </div>
      </section>

      <section className="content-grid">
        <article className="panel">
          <h3>Continue watching</h3>
          <p>Resume the titles that are currently in progress.</p>
        </article>
        <article className="panel">
          <h3>Recently added</h3>
          <p>Surface the newest items saved to the queue.</p>
        </article>
      </section>
    </div>
  )
}

export default HomePage
