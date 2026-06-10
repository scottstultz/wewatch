# WeWatch

WeWatch is a full-stack app for discovering, tracking, and organizing movies and TV shows. Users sign in with Google or email+password, search for titles via the TMDB API, and manage personal or shared watchlists with episode-level tracking.

---

## Features

### Content Discovery
- Search for movies and TV shows via TMDB
- View title details: poster, overview, release year, content type

### Watchlist Management
- Create multiple named watchlists
- Share watchlists with other users (roles: Owner, Editor, Viewer)
- Track status per title: Want to Watch, Watching, Watched
- Move titles between statuses or remove them
- Paginated library view with status filtering

### Episode Tracking
- Per-episode watched/unwatched toggle for TV shows
- Bulk mark an entire season as watched or unwatched
- Library tiles show the next unwatched episode by air date with runtime
- Caught-up state distinguishes ended series ("Series complete") from airing ones ("All caught up")

### Authentication
- Google Sign-In (OAuth2 ID token exchange)
- Email + password registration and sign-in (BCrypt)
- Provider-agnostic self-issued JWT (HS256, 1-hour expiry)
- Email allowlist restricts registration to approved users
- Sign-out from sidebar (desktop) and header (mobile)

### TMDB Metadata Cache
- Server-side cache for TV show and episode metadata (7-day configurable TTL)
- Async prewarm on watchlist add — all seasons/episodes cached immediately
- Startup backfill for titles added before the cache was deployed
- Cache-through reads: stale entries refreshed transparently on access

---

## Tech Stack

### Frontend
- React + TypeScript
- Vite
- Plain CSS (mobile-first responsive)

### Backend
- Java 21
- Spring Boot 3.5
- Spring Security (JWT resource server)
- Flyway (database migrations)

### Data / Infra
- PostgreSQL 17
- Docker / Docker Compose
- GitHub Actions (CI)
- Doppler (secrets management)
- Railway (production hosting)

### External APIs
- TMDB v3 (search, title details, season/episode metadata)

---

## Getting Started

### Prerequisites

- [Git](https://git-scm.com/)
- [Node.js](https://nodejs.org/) 25+ with `npm`
- Java 21+
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) or Docker Engine with Compose
- [Doppler CLI](https://docs.doppler.com/docs/install-cli) for secrets injection

The backend uses the Maven wrapper (`./mvnw`), so a global Maven install is optional.

### Clone the Repository

```bash
git clone git@github.com:scottstultz/wewatch.git
cd wewatch
```

### Secrets

WeWatch uses [Doppler](https://doppler.com) to manage secrets. No `.env` files or shell exports needed.

**First-time setup:**

```bash
doppler login
doppler setup
```

Select the `wewatch` project and `dev` config when prompted.

**Required environment variables:**

| Variable | Purpose |
|---|---|
| `GOOGLE_CLIENT_ID` | Google OAuth2 client ID for verifying Google ID tokens |
| `JWT_SECRET` | HMAC secret for signing WeWatch JWTs (min 32 chars) |
| `TMDB_API_KEY` | TMDB v3 Bearer token |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | PostgreSQL connection (defaults: localhost / 5432 / wewatch) |
| `DB_USER`, `DB_PASSWORD` | PostgreSQL credentials (defaults: wewatch / wewatch) |

### Run the Full Stack with Docker

The simplest way to run all services together:

```bash
doppler run -- docker compose up --build
```

This starts PostgreSQL, the backend, and the frontend. The app is available at `http://localhost:3000`.

Use `--build` the first time or after code changes. To stop:

```bash
docker compose down
```

### Run Services Individually

Use three terminals:

1. **PostgreSQL:**
   ```bash
   docker compose up -d postgres
   ```

2. **Backend:**
   ```bash
   cd backend
   doppler run -- ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

3. **Frontend:**
   ```bash
   cd frontend
   npm install
   doppler run -- npm run dev
   ```

**Default local ports:**

| Service | Port | URL |
|---|---|---|
| Frontend | 5173 | http://localhost:5173 |
| Backend | 8080 | http://localhost:8080 |
| PostgreSQL | 5432 | localhost:5432 |

### Testing

```bash
cd backend
doppler run -- ./mvnw test
```

250 tests, all passing. Tests use Mockito; controller tests use `@WebMvcTest` with `MockMvc`.

---

## Repository Structure

```text
wewatch/
├── backend/          # Spring Boot API (Java 21)
│   └── src/main/resources/db/migration/   # Flyway migrations (V1–V9)
├── frontend/         # React + TypeScript + Vite
├── .github/          # CI workflows, issue templates
└── README.md
```

---

## Development Workflow

### Issue-Based Development

All work is tracked via GitHub Issues and GitHub Projects. Issues move through: Backlog, Ready, In Progress, In Review, Done.

### Branch Naming

```
{type}/{issueNumber}-short-description
```

Types: `bug`, `chore`, `feature`

Examples:
- `feature/73-add-pagination`
- `bug/164-null-air-date-next-episode`
- `chore/71-flyway-migrations`

### Commit Messages

[Conventional Commits](https://www.conventionalcommits.org/) format with issue number:

```
feat: add episode-level tracking (#73)
fix: handle null air_date in next-episode query (#164)
chore: replace schema.sql with Flyway migrations (#71)
```

### Pull Requests

PRs follow a four-section format: **Summary**, **Changes**, **Why**, **Testing**. Each PR maps to a GitHub issue and closes it on merge.

### Principles

- Build in vertical slices
- Favor clarity and maintainability over premature complexity
- Treat the project like a real production app
- Small, focused PRs that are easy to review

---

## Roadmap

### Completed
- Content search and detail view (TMDB integration)
- Status-based tracking (Want to Watch, Watching, Watched)
- Personal library with pagination and status filtering
- User authentication (Google + email/password)
- Email allowlist for controlled access
- Shared watchlists with member roles
- Episode-level tracking with bulk season operations
- TMDB metadata cache with async prewarm
- Next unwatched episode display on library tiles
- Production deployment (Railway, we-watch.app)
- CI pipeline (GitHub Actions)

### Planned
- Title detail page
- Streaming provider integration
- Advanced filtering and sorting
- Recommendation system
- Ratings and reviews
