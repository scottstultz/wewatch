function ProfilePage() {
  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Profile</p>
          <h2>Placeholder preferences and account view.</h2>
          <p>
            This route is reserved for account settings, preferences, and
            future integrations.
          </p>
        </div>
      </section>

      <section className="stack-list">
        <article className="panel">
          <h3>Preferences</h3>
          <p>Playback, notifications, and discovery preferences will go here.</p>
        </article>
        <article className="panel">
          <h3>Connected services</h3>
          <p>Future streaming-provider and account integrations live here.</p>
        </article>
      </section>
    </div>
  )
}

export default ProfilePage
