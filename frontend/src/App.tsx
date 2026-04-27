function App() {
  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">WeWatch</p>
          <h1>Frontend Shell</h1>
        </div>
      </header>

      <main className="workspace">
        <section className="panel panel-primary">
          <h2>Ready for UI development</h2>
          <p>
            React, TypeScript, and Vite are configured. This shell is the base
            for the discovery, watchlist, and tracking workflows.
          </p>
        </section>

        <section className="panel-grid" aria-label="Project structure preview">
          <article className="panel">
            <h2>Components</h2>
            <p>Shared UI building blocks and layout primitives.</p>
          </article>

          <article className="panel">
            <h2>Pages</h2>
            <p>Route-level screens and feature entry points.</p>
          </article>

          <article className="panel">
            <h2>Services</h2>
            <p>API clients, data access helpers, and network wiring.</p>
          </article>

          <article className="panel">
            <h2>Types</h2>
            <p>Shared TypeScript types for UI and API contracts.</p>
          </article>
        </section>
      </main>
    </div>
  )
}

export default App
