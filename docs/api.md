# API Documentation

The backend REST API is documented interactively via [springdoc-openapi](https://springdoc.org/)
— annotations on the controllers and DTOs in `backend/src/main/java/com/wewatch/api` generate
both a browsable Swagger UI and a machine-readable OpenAPI spec. See
[`docs/architecture.md`](architecture.md) → "API Documentation" for the setup and the
opt-in exposure decision (#343).

## Local dev

With the backend running (`doppler run -- ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`,
default port 8080, no context path):

- Swagger UI: http://localhost:8080/swagger-ui.html
- Raw OpenAPI spec (JSON): http://localhost:8080/v3/api-docs

Both are off by default in every configuration (#343) — the `local` profile is the only one
that enables them, and the paths require authentication anywhere else.

## Authenticating in Swagger UI

Most endpoints require a WeWatch JWT. Use `POST /api/auth/token` (Google/email credential
exchange) or `POST /api/auth/register` (email+password sign-up) via "Try it out" to obtain a
token, then click **Authorize** and paste it in as a bearer token. `GET /api/health` and the
`/api/auth/**` endpoints don't require authorization.

## Endpoint index

For a quick reference without booting the app — see Swagger UI for full request/response
schemas and status codes.

| Resource | Base path | Purpose |
|---|---|---|
| Health | `/api/health` | Liveness check |
| Auth | `/api/auth` | Credential exchange for WeWatch JWTs |
| Users | `/api/users` | Current user profile, streaming provider settings |
| Titles | `/api/titles` | TMDB search/detail, local title resolution, seasons/episodes |
| Title Ratings | `/api/titles/{titleId}/rating` | Thumbs up/down ratings |
| Watchlists | `/api/watchlists` | Create/manage shared watchlists and membership |
| Watchlist Entries | `/api/watchlists/{watchlistId}/entries` | Titles on a watchlist and their status |
| Episode Progress | `/api/watchlists/{watchlistId}/entries/{entryId}/episodes` | Per-episode and bulk-season watched state |
| Suggestions | `/api/suggestions` | Personalized Discover shelves, dismissals, and the taste-ranked genre browse feed |
| Watch Providers | `/api/watch-providers` | Streaming providers by region |
