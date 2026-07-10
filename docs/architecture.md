# Architecture

This document covers the design of WeWatch's non-obvious subsystems — auth, TMDB metadata
caching, and the suggestion pipeline — organized by subsystem rather than by the chronology of
the issues that built them. Issue numbers are cited inline as references into history, not as
a reading order. For the historical, pre-implementation design sketch this doc replaces, see
[`docs/archive/mvp-design-notes.md`](archive/mvp-design-notes.md).

Stack: Spring Boot 3.5 / Java 21 backend (`backend/src/main/java/com/wewatch/api`), React +
TypeScript + Vite frontend, PostgreSQL via Flyway migrations, TMDB v3 as the external content
source.

## Auth & Token Exchange

Frontend sends a provider credential (a Google ID token or an email+password pair) to
`POST /api/auth/token`. The backend validates it against the provider and returns a
self-issued WeWatch JWT (HS256, 24-hour expiry — see [Session lifetime & hardening](#session-lifetime--hardening-293)). Every other endpoint only ever sees WeWatch
JWTs — provider-specific logic is isolated to the exchange endpoint, so adding a new provider
means writing one new validator and adding one branch in `AuthController.exchangeToken`; JWT
issuance, validation, and every downstream endpoint are untouched.

- `JwtTokenService` signs and decodes tokens with HMAC-SHA256 using the `JWT_SECRET` env var.
- `GoogleTokenValidator` validates Google ID tokens via `https://oauth2.googleapis.com/tokeninfo`
  and checks the `aud` claim against `GOOGLE_CLIENT_ID` (this is separate from `JWT_SECRET`,
  which validates WeWatch's own tokens).
- `WeWatchJwtAuthenticationConverter` extracts the user id from the `sub` claim and resolves it
  via `UserService.findById` — no find-or-create on every request.
- Email+password: `UserService.registerWithPassword` / `authenticateWithPassword`, BCrypt via a
  `PasswordEncoder` bean. `users.password_hash` is nullable — Google-only users have no password.
- **Allowlist**: the `allowed_emails` table (V8) gates both registration and token exchange.
  `AuthController.requireAllowedEmail` throws a 403 otherwise.

### Session refresh (#207)

Sliding re-issue rather than a separate refresh-token flow. `TokenRefreshFilter` runs after
`BearerTokenAuthenticationFilter` and, when an authenticated request's token is within
`jwt.refresh-window-seconds` (default 43200s / 12h) of expiry, returns a fresh JWT in the
`X-Refreshed-Token` response header. The frontend's `apiFetch` picks it up and dispatches a
`wewatch:token-refreshed` event; `AuthContext` listens for it and also runs an expiry timer that
signs the user out gracefully ("Your session expired") if a token lapses without a refreshed
request happening first. The window is half the token lifetime so requests in the back half slide
the session forward, while a request in the front half leaves the token untouched — this keeps
daily-active users perpetually renewed without minting a token on every response.

### Session lifetime & hardening (#293)

The token lifetime is **24h** (`jwt.expiration-seconds`, default 86400s). It was originally 1h,
which logged users out during the common watch-and-return pattern: sign in → brief burst of
activity → idle through a show → return. That burst happens early in the token's life, so it
never lands in the refresh window and no fresh token is minted; the idle stretch then outlives a
1h token. The lifetime only needs to exceed the longest realistic single idle gap (a movie), so
24h comfortably covers any single sitting, and the sliding refresh above keeps active sessions
alive indefinitely.

**Accepted trade-off:** the JWT is stateless with **no server-side revocation** — a leaked token
is valid until it expires, and the sliding refresh lets an *actively used* stolen token
self-renew. A 24h lifetime is acceptable because WeWatch is a low-sensitivity, **allowlist-gated**
personal app (the `allowed_emails` table gates every sign-in). If WeWatch ever opens to
non-allowlisted users, lifetime is no longer a safe substitute for revocation, and we should first
implement proper hardening: a **short-lived access token** (minutes) plus a **revocable,
server-side refresh token** — which also enables real logout and "sign out all devices". Until
then, keep the lifetime bounded (don't push it to days) and treat `JWT_SECRET` rotation as the
only blunt-instrument way to invalidate all outstanding tokens.

### Tab-restore resilience (#242)

Mobile browsers freeze or bfcache a backgrounded tab instead of reloading it, so neither the
`AuthProvider` bootstrap effect nor the expiry `setTimeout` fire on restore — the app could
resume showing an expired session as valid. `AuthContext` re-validates on `pageshow` and
`visibilitychange`, running the same graceful sign-out path when the stored token has expired.
A top-level `ErrorBoundary` (wraps `<App>` in `main.tsx`) renders a "Something went wrong /
Reload" fallback instead of unmounting to a blank screen on any rehydration error. Bootstrap
`getCurrentUser` failures only clear the token on a confirmed 401 — a transient network blip on
resume no longer forces a sign-out.

## TMDB Metadata Cache

`tmdb_title_cache`, `tmdb_season_cache`, and `tmdb_episode_cache` (V9–V12) hold title, season,
and episode metadata with a 7-day TTL (`tmdb.cache.ttl-days`), read through `TmdbCacheService`.
Reads are cache-through: stale entries refresh transparently on access. The cache is populated
two ways — asynchronously (`@Async prewarmShow` / `prewarmMovie`) when an entry is added to a
watchlist, and by `TmdbCacheBackfill` at startup for titles added before the cache existed.
Season summaries are upserted by `upsertTvCache` (shared by the season-list fetch, prewarm, and
backfill paths) and served by `getSeasons` with no TMDB call while fresh; season 0 ("Specials")
is filtered on read.

The same detail calls that populate `tmdb_title_cache` also carry credits, keyword names,
collection membership, and watch-provider availability — these ride the existing request via
`append_to_response=credits,watch/providers` rather than costing extra TMDB calls, and a null
block in a refresh response keeps the prior cached value instead of clearing it:

- **Credits** (#269): top 5 cast by billing order + directors, as JSON columns `top_cast` /
  `directors` (`CachedPerson` + a JSON converter, not the CSV pattern used for genre/keyword
  ids, because names carry arbitrary characters).
- **Keywords** (#271): `keyword_ids` (scoring) plus a parallel `keywords` JSON column with
  names (labeling), written together by `cacheKeywords`.
- **Collections** (#272): scalar `collection_id` / `collection_name` columns, movie-only
  (`applyCollection`, called from `upsertMovieCache` — TV has no collections).
- **Watch providers** (#270): `watch_providers`, a JSON region → provider-id list for flatrate
  (subscription) offers only.

## Suggestion Pipeline

`SuggestionService` builds per-watchlist Discover shelves from watchlist content: per-seed
shelves (TMDB recommendations/similar), genre-profile shelves (TMDB discover), and a set of
specialty and exploration shelves. Results are cached per watchlist in an in-process Caffeine
cache (`suggestions.cache.ttl-minutes` / `suggestions.cache.max-size`, defaults 30 min / 1000)
and recomputed asynchronously on entry changes — this is a single-node assumption; horizontal
scaling would need a shared cache store.

### Daily rotation

Shelves rotate daily (#231) but are stable within a day: a `Random` seeded with
`hash(watchlistId, epochDay)` drives every rotation decision, and a `Clock` bean makes the
epoch day injectable in tests. Three things are day-seeded:

- **Seed selection**: eligible seeds (WATCHING/WANT_TO_WATCH entries) partition into rich/thin
  tiers by cached vote count (`RICH_SEED_VOTE_COUNT_GTE = 300`, from `tmdb_title_cache
  .vote_count`; NULL counts as thin), each tier shuffled independently, rich tier drawn first
  (#266). A low-vote or uncached seed can still surface, but only after the well-known ones —
  without tiering, an obscure or low-signal seed had equal odds of hogging a rotation slot.
- **Page depth per feed** (#249): each feed draws one TMDB page from a bounded, feed-specific
  range rather than always page 1, deepening the reachable candidate pool without adding calls.
  Recommendations/similar stay shallow (`MAX_SEED_FETCH_PAGE = 3` — those endpoints genuinely
  run out). Discover-backed shelves (genre-profile, `NEW_RELEASES`) draw pages 1–6
  (`MAX_DISCOVER_FETCH_PAGE`). `TRENDING` stays shallow (1–3, `trending/week` thins fast).
  `HIDDEN_GEMS` draws from a mid-deep band [4, 18] — pages 1–3 of `vote_average.desc` are
  identical for every user with the same genre profile, so skipping them is what makes the
  shelf feel distinct, while page 18+ is thin enough to trigger the page-1 fallback too often.
  A deep page that comes back empty falls back to page 1. TMDB call budget per compute stays
  bounded regardless of depth: one page per feed, doubling only on a fallback.

### Taste-profile construction

Every profile-building pass (genre profile, keyword affinities, person profile, and the
discover-filter genre cut) reads the same per-entry inputs and applies two multipliers before
counting:

- **Status/rating weight** (`profileWeight`, #232 + #273): WATCHING/WATCHED count double a
  WANT_TO_WATCH entry for genre profiling — but seed *eligibility* (which entries can anchor a
  "Because you added X" shelf) stays WATCHING/WANT_TO_WATCH only, so a finished title shapes the
  profile without generating its own shelf. A thumbs rating overrides the status weight
  entirely: UP → 4.0 (above WATCHED's 2), DOWN → −2.0. This closes a gap the status-only model
  had — finishing a hated show used to strengthen its genres in the profile just by virtue of
  being WATCHED.
- **Recency decay** (#274): every contribution is scaled by `0.5^(ageDays / half-life)` against
  `updated_at` (falling back to `added_at`), floored so old history never zeroes out fully.
  Defaults: 90-day half-life, 0.2 floor (reached around 209 days), both configurable via
  `SuggestionTuningProperties`. Age is computed in whole days off the injected `Clock`, so
  profiles are stable within a day and the #231 same-day reproducibility guarantee holds.
  Decay applies to rating weights too — a stale dislike stops steering as hard, same as a stale
  like fades. One exemption: the person-affinity recurrence floor (below) stays an undecayed
  plain count, matching its own exemption from rating weighting.

Frequency-counted profiles (keyword affinities, person profile, the discover genre cut) instead
weight each contributing title by `signalWeight` — UP 2.0, DOWN −1.0, unrated 1.0 — and drop
any keyword/genre/person whose weighted frequency nets non-positive, so something carried only
by disliked titles can't make the profile. Two specifics:

- **Keyword affinities** (`buildKeywordAffinities`, #271): top `MAX_KEYWORDS` by frequency,
  ties broken by id for day-to-day stability. Names ride alongside ids in the cache (see TMDB
  cache section above); rows without a cached name can't seed a labeled shelf.
- **Person profile** (`buildPersonProfile`, #269): counts each person across cast + director
  credits, floored at `PERSON_MIN_TITLE_COUNT = 2` — recurrence is the signal (unlike keywords,
  which count once per title), so the floor is a plain appearance count over non-DOWN-rated
  titles, not the weighted score. One loved title still isn't "you keep watching this person";
  ranking within the profile does use the weighted score.

### Scoring & jitter

A candidate's score is genre affinity plus per-signal boosts read from one batch cache lookup
per seed (`cacheSignalBoosts`): `KEYWORD_MATCH_WEIGHT = 2.0` per shared top-5 keyword,
`PERSON_MATCH_WEIGHT = 3.0` per shared top-5 person, `STREAMABLE_BOOST = 2.5` when the
candidate is available on a configured watch provider. Uncached candidates get no boost.

Day-seeded jitter (#248, proportional since #267) then perturbs the stable sort:
`rankByTasteProfile` and the `TRENDING` rerank each add a per-candidate ± offset
(`jitterByCandidate`) before sorting. Two things drove the design:

- TMDB recommendations/similar are stable and the pre-#248 scoring was deterministic, so the
  daily reshuffle only ever rotated exact-score ties — the same top scorers floated to the top
  every day, and once suppressed by the (now-retired) hard-suppression scheme, boomeranged
  straight back the moment they aged out.
- A flat jitter amplitude (the original ±1.0) rivaled the entire signal on sparse profiles (a
  zero-affinity candidate could out-jitter a multi-signal match) while being negligible on rich
  ones, since profile weights are unnormalized sums that grow with watchlist size.

So the amplitude is proportional to the candidate's own base score:
`max(SCORE_JITTER_FLOOR = 0.25, SCORE_JITTER_FRACTION = 0.15 × score)`. This reorders near-peers
at every profile scale while a genuinely dominant score (roughly 2× the runner-up) still wins
every day, and the floor keeps zero/low-score candidates rotating without letting them outrank
a real genre match. `jitterByCandidate` draws exactly one `nextDouble` per distinct candidate
in list order and scales it after the draw, so rng consumption — and same-day reproducibility —
is independent of amplitude.

### Shelf filling & diversification

`fillShelf` is the common point every score-ranked seed feed and every order-only discover feed
flows through, which is why cross-cutting shelf policy lives here rather than in each feed.

- **Genre-cluster diversification** (#265): a same-genre cap (`MAX_PER_GENRE_CLUSTER = 4`)
  applies only to feeds with a real genre mix — per-seed recommendations/similar,
  `FINISHED_SEED`, `TRENDING`. Discover-backed shelves (`GENRE_PROFILE`, `NEW_RELEASES`,
  `HIDDEN_GEMS`) are exempt: they're already filtered to the user's top genres by construction,
  so the cap used to chop a 20-result page down to ~4 and starve the shelf. The cap keys on a
  candidate's full genre set — it's skipped only when *every* genre the candidate carries is
  already saturated — rather than TMDB's arbitrary first-listed genre, so a candidate bringing
  any fresh genre still gets in.
- **Soft recency penalty** (#264, replacing binary suppression + top-up): applied as a
  positional demotion, a stable re-sort by `index + weight × RECENCY_DEMOTION (16)`, where
  weight comes from `SuggestionImpressionService.recencyWeights` — 1.0 for shown yesterday,
  decaying linearly to 1/window at the suppression window's edge (default 7 days), and absent
  beyond it. Positional (not score) demotion was the deliberate fix for two failure modes of
  the old binary suppression: a shown-yesterday title sinks past a full shelf instead of being
  hard-excluded, so shelves fill toward `MAX_SHELF_SIZE` instead of pinning at whatever was left
  after suppression; and a window-edge title recovers most of its rank (~2 positions) instead of
  snapping back in all at once via the old top-up mechanism (#246), which is deleted along with
  it — every served title is simply re-recorded, safe under a continuous penalty since it just
  starts sinking again. The penalty follows the user, not the watchlist (#247): a shared list
  answers to the union of every member's impressions.
- **Thin-shelf gating + catch-all pooling** (#266): a per-seed or finished-seed shelf stands
  alone only if it clears `SEED_SHELF_MIN_FRESH = 6` fresh (non-penalized) titles after
  `fillShelf`. Below that floor, the shelf releases its dedup claims and pools its full
  candidate list into a single aggregate; all such leftovers across every under-floor shelf are
  merged, deduped, and re-ranked together as one "More picks for you" shelf
  (`rankByTasteProfile` on the combined pool). This replaced 3–5-tile stub shelves from niche
  seeds or repeat-heavy feeds — their best candidates now surface in the aggregate instead of
  rendering a nearly-empty shelf of their own.

### Dismissals

"Not interested" (#268, `suggestion_dismissals`, one row per `(user_id, tmdb_id)`) is permanent
and explicit-intent, unlike the impressions table's time-windowed, continuously-recorded
penalty above — it's undoable but doesn't decay. Dismissed ids seed the cross-shelf `seen` dedup
set at the start of `compute`, so a dismissal excludes a title from every shelf kind at the one
point they all flow through, and a dismissed title can never re-enter via the thin-shelf
`seen.remove` release (that release only touches ids that actually made it into a shelf).
Dismissals are user-scoped like impressions (union over a shared list's members).

### Specialty shelves

**Franchise continuation** (#272, `FRANCHISE`) is structurally different from every other
shelf: it's built in `compute` *before* the per-seed loop, not inside the daily-rotated
exploration switch, so it isn't capped by `MAX_EXPLORATION_SHELVES` and isn't gated by
`MIN_SHELF_SIZE` — a single remaining sequel is still the single best suggestion. It gets first
pick of the `seen` dedup set so a sequel can't be crowded out by a generic candidate. Candidates
come only from WATCHED/WATCHING movie entries' cached collections (WANT_TO_WATCH is excluded —
franchise continuation is a completed-interest signal); the day-seeded rng picks one qualifying
collection, and results sort by release date, not taste-profile score. It's exempt from both
score jitter and genre diversification — the franchise itself is the coherence axis — and from
the recency penalty, since a positional demotion could only reorder the shelf (never shrink it)
and mixing in cross-shelf exposure would break the release-order contract; impressions are still
recorded on serve. Unreleased parts are excluded outright (a null release date counts as
unreleased, deliberately the opposite of the episode-progress null-means-aired rule). Owned
parts — including the seed title itself, which TMDB returns as one of the collection's parts —
fall out through the shared `seen` set, so the shelf naturally empties once the franchise is
complete.

**Keyword shelves** (#271, `KEYWORD`) and **person shelves** (#269, `PERSON`) join the
day-rotated exploration slot alongside `TRENDING`, `NEW_RELEASES`, and `HIDDEN_GEMS`, capped at
`MAX_EXPLORATION_SHELVES = 2` per compute (#235) — a kind that can't fill yields its slot to the
next. Both are exempt from genre diversification, matching `FRANCHISE`, since their theme (the
keyword or the person) is the shelf's coherence axis rather than genre mix. `KEYWORD` picks one
named keyword from the profile via the day-seeded rng and discovers a shallow page (1–3) of
either media type (whichever the profile leans toward) — single-keyword catalogs thin fast, so
rotation comes from the keyword draw, not page depth; it's discover-backed and therefore takes
the watch-provider filter below. `PERSON` is movie-only (TMDB's TV discover has no `with_people`
filter), always page 1 since filmographies are shallow, and labels itself "Directed by X" when
the person directs at least as often as they act, "More with X" otherwise.

### Watch providers (#270)

Users record their streaming services (`users.watch_region` + `users.watch_provider_ids`, edited
via `PATCH /api/users/{id}`). `SuggestionService.compute` resolves a `ProviderContext` from the
union of configured members' provider ids — but only enables provider-awareness when every
configured member shares one watch region. Conflicting regions disable it for that list. This is
a deliberate simplification, not an oversight: TMDB's discover endpoint takes exactly one
`watch_region`, so there's no way to filter for "available in region A for user 1 and region B
for user 2" in a single discover call, and provider ids themselves aren't comparable across
regions. Discover-backed shelves pass `with_watch_providers` + `watch_region` +
`with_watch_monetization_types=flatrate` and are marked `providerFiltered` in the API response;
recommendations/similar/pooled candidates instead get the flat `STREAMABLE_BOOST` described
above, since those endpoints have no provider filter to push down to TMDB. Served titles carry
`providerIds` badges from the cached region-scoped data; a null badge means the cache hasn't
seen that title/region pair yet, not that it's unavailable — the title detail page fills that
gap with a live call.

### Tuning surface (#288)

The taste-profile weights, signal boosts, jitter shape, recency decay, and positional demotion
constant above are `@ConfigurationProperties("suggestions.tuning")` fields
(`SuggestionTuningProperties`) rather than `private static final` constants, so they can be
overridden per-environment without a code change. Defaults equal the original hardcoded values,
so an unset property changes nothing. Structural knobs — shelf sizes, page-depth bounds, TMDB
call budgets — deliberately stay plain constants: they bound cost and shape, not taste, and
don't belong on the same tuning surface. An offline harness
(`backend/src/test/java/com/wewatch/api/tuning/`, run via `./mvnw test -Ptuning`) replays fixture
watchlists through the real `SuggestionService` against a deterministic synthetic TMDB catalog
(no network) and diffs shelves across parameter sets — for evaluating a tuning change's effect
before shipping it, without touching production data.

## API Documentation (#297)

The REST API is documented via [springdoc-openapi](https://springdoc.org/)
(`springdoc-openapi-starter-webmvc-ui`), generating both Swagger UI and an OpenAPI 3 spec from
annotations on the existing controllers/DTOs (`@Tag`, `@Operation`, `@ApiResponses`, `@Schema`)
rather than a hand-maintained spec file. `OpenApiConfig` registers the API metadata and a
`bearerAuth` `SecurityScheme` (HTTP bearer, JWT format) as a global security requirement, which
is what powers Swagger UI's "Authorize" button against the app's self-issued JWTs; the two public
controllers (`HealthController`, `AuthController`) override it with an empty
`@SecurityRequirements` so Swagger UI reflects that they don't need a token.

`SecurityConfig` permits `/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` alongside the
existing `/api/health` and `/api/auth/**` matchers — otherwise `anyRequest().authenticated()`
would block Swagger UI itself before a caller could authenticate. Swagger UI is served directly
from the backend, not proxied through the Vite dev server, so no CORS changes were needed.

**Local-only exposure:** `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` are set to
`false` in `application-prod.properties` only. There's no Actuator or other admin surface in this
app to model the gating on, so this follows the existing per-profile `.properties` split (CORS
origins, datasource credentials) instead. The API is allowlist-gated but the docs endpoints
themselves are unauthenticated by necessity (a caller needs the spec before they have a token),
so disabling them in prod avoids exposing the full internal API shape — every route, DTO field,
and error code — to the public internet with no corresponding benefit, since the only consumer is
the first-party frontend. See [`docs/api.md`](api.md) for how to reach Swagger UI locally.

## Person Endpoint (#304)

`GET /api/people/{personId}` (`PersonController`) returns a person's bio plus their acting
filmography, backed by TMDB's `/3/person/{id}?append_to_response=combined_credits`. As with
`getMovieDetail`, `TmdbClient` returns the raw TMDB record and the controller maps it to a DTO;
the credit selection lives in `PersonCreditsMapper` so its rules are unit-testable on their own.

**Why this uses `combined_credits` while the PERSON suggestion shelf uses
`discover?with_people`.** These look like the same query and are not. `TmdbClient.discoverByPerson`
wraps `/3/discover/movie?with_people=`, and TMDB offers no equivalent for TV — `/3/discover/tv`
has no people filter at all. That's fine for the `PERSON` suggestion shelf (#269), which is
movie-only by design, but reusing it for the person page would silently drop every TV credit,
which is wrong in an app that tracks both. `combined_credits` returns the bio *and* the full
movie+TV filmography in one request. **The asymmetry is deliberate; collapsing the two call sites
into one would quietly break the person page's TV credits.**

**Credit selection.** Acting credits (`cast`) only; crew is out of scope. Entries are dropped when
the media type is neither `movie` nor `tv` (TMDB emits others), when there's no `poster_path`
(nothing to render), when the credit is a talk/news/reality appearance, or when the character
marks it as an appearance-as-yourself. Survivors are sorted by `popularity` descending, deduped by
`(mediaType, id)` — a person can hold two credits on one title via a dual role or a recurring TV
guest arc — and capped at 100.

**No vote-count floor.** A `vote_count >= N` filter reads sensible for movies but silently deletes
real TV credits, whose vote counts run an order of magnitude lower. (Bryan Cranston's filmography
is ~58% TV.) Requiring a poster and dropping self-appearances is what actually removes the junk.

**Removing late-night junk takes two signals, not one.** Filtering on `character` alone does not
work: TMDB leaves the character *blank* on many talk-show credits — "LIVE with Kelly and Mark" and
"The Tonight Show with Jay Leno" both come back with `character: ""` — and they outrank real
credits on popularity, so they land near the top of the page. The reliable signal is the genre
tag, so the mapper drops TMDB's TV genres Talk (10767), News (10763), and Reality (10764). Those
ids exist only in TMDB's TV genre list, so matching them unconditionally cannot drop a movie. The
`character` check still runs alongside it, catching self-appearances embedded in otherwise
scripted titles (`Self`, `Self - Guest`, `Himself`, `Herself`, case-insensitive with suffixes).

Two deliberate non-filters. Blank characters are **kept** — on a movie a blank character usually
means a real film whose cast TMDB hasn't detailed yet. And playing a fictionalized version of
yourself is a real role, which TMDB credits under the person's own name rather than `Self`, so
Keanu Reeves in *Always Be My Maybe* survives. Documentaries (genre 99) are also kept, since
filtering them would drop legitimate documentary work; the cost is that promo series like
"HBO First Look" can still appear, far down the list.
