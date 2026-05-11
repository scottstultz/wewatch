import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { UnauthorizedError, searchTitles } from '../services/api'
import type { TitleSearchResponse } from '../types/api'

function DiscoverPage() {
  const { token, signOut } = useAuth()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<TitleSearchResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)

  useEffect(() => {
    if (!query.trim()) {
      setResults([])
      setSearched(false)
      return
    }

    const timer = setTimeout(async () => {
      if (!token) return
      setIsLoading(true)
      setError(null)
      try {
        const data = await searchTitles(query, token)
        setResults(data)
        setSearched(true)
      } catch (e) {
        if (e instanceof UnauthorizedError) {
          signOut()
          navigate('/sign-in', { replace: true })
        } else {
          setError('Search failed. Please try again.')
        }
      } finally {
        setIsLoading(false)
      }
    }, 300)

    return () => clearTimeout(timer)
  }, [query, token, signOut, navigate])

  return (
    <div className="page">
      <section className="hero-panel compact-panel">
        <div className="hero-copy">
          <p className="section-kicker">Discover</p>
          <h2>Find something to watch.</h2>
          <input
            className="search-input"
            type="search"
            placeholder="Search movies and TV shows…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
          />
        </div>
      </section>

      <section className="stack-list">
        {isLoading && <p className="search-status">Searching…</p>}

        {error && <p className="search-status search-status-error">{error}</p>}

        {!isLoading && searched && results.length === 0 && (
          <p className="search-status">No results for &ldquo;{query}&rdquo;.</p>
        )}

        {results.length > 0 && (
          <div className="title-grid">
            {results.map((title) => (
              <article key={`${title.externalSource}-${title.externalId}`} className="title-card">
                {title.posterUrl ? (
                  <img
                    className="title-poster"
                    src={title.posterUrl}
                    alt={title.name}
                    loading="lazy"
                  />
                ) : (
                  <div className="title-poster title-poster-empty" />
                )}
                <div className="title-card-body">
                  <span className="title-type-badge">
                    {title.type === 'MOVIE' ? 'Movie' : 'TV Show'}
                  </span>
                  <p className="title-name">{title.name}</p>
                  {title.releaseDate && (
                    <p className="title-year">{new Date(title.releaseDate).getFullYear()}</p>
                  )}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

export default DiscoverPage
