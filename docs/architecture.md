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

### Auth attempt throttling (#318)

The `/api/auth/**` endpoints are `permitAll`, so password sign-in accepted unlimited attempts.
Registration is allowlist-gated, but an allowlisted user's password could still be brute-forced —
BCrypt slows each guess but nothing capped the stream. This pairs badly with the #293 trade-off:
a guessed credential is a 24h stateless session with no kill switch. Throttling closes that
cheapest attack path without revisiting the token design.

`LoginAttemptService` (in-memory, Caffeine-backed) enforces two independent fixed-window buckets on
failed attempts: **per-email** (`app.auth.throttle.email-max-attempts`, default 5) and **per-IP**
(`app.auth.throttle.ip-max-attempts`, default 20), each over `app.auth.throttle.window-seconds`
(default 900). The IP limit is deliberately higher than the email limit so a shared household IP
among allowlisted users isn't falsely locked out. `AuthController` checks both buckets on every
auth flow (token exchange, registration, Google), records a failure on invalid-credential and
allowlist-rejection paths, and clears the email bucket on a successful sign-in. Over the threshold,
requests get **429** with a `Retry-After` header (via `ApiExceptionHandler`, standard
`ApiErrorResponse` shape). Caffeine's `expireAfterWrite` evicts stale keys and `maximumSize` bounds
memory against distinct-key flooding.

**Single-instance constraint:** the buckets are per-node in-memory state, so a horizontally scaled
deployment would throttle independently per node — move to a shared store (e.g. Redis) before
scaling out.

### Client IP behind the proxy (#336)

The per-IP bucket originally keyed on `getRemoteAddr()`, the peer that opened the socket. But nginx
proxies `/api/` to the backend and the frontend calls relative `/api` paths, so in the deployed
topology that peer is *nginx* for every user: one shared bucket, and 20 bad passwords from anywhere
locked out every sign-in for the rest of the 15-minute window. The per-IP throttle had exactly
inverted its purpose — it was a cheap global-lockout DoS rather than per-attacker isolation. (The
per-email bucket was unaffected, which is why password brute-forcing stayed capped throughout.)

`ClientIpResolver` resolves the real client, and the order of its checks is the security property:

1. **Peer first.** If `getRemoteAddr()` is not a configured trusted proxy, return it and ignore the
   forwarding headers entirely. Skipping this step is worse than the bug: a direct caller could set
   a fresh `X-Forwarded-For` per request and never fill a bucket at all, bypassing the IP throttle
   outright.
2. **Right to left.** From a trusted peer, walk `X-Forwarded-For` backwards, skipping hops that are
   themselves trusted proxies, and take the first untrusted one. Direction is load-bearing: nginx's
   `$proxy_add_x_forwarded_for` *appends* the peer to whatever the client sent, so a client that
   forges the header produces `"1.2.3.4, <real client>"` — everything left of the rightmost
   untrusted entry is attacker-controlled. Reading left-to-right would hand the caller its own
   bucket key back.
3. **Fall back** to `X-Real-IP` (nginx overwrites any client-supplied value with `$remote_addr`),
   then to the peer.

`app.auth.throttle.trusted-proxies` takes a comma-separated list of literal addresses or CIDR
blocks, v4 or v6, matched by prefix bits. It defaults to loopback plus the RFC 1918 ranges — the
same set as Tomcat's `internal-proxies` — which covers the docker-compose bridge network with no
extra configuration. Blank trusts nothing and reverts to peer-only behavior. Bad entries are
discarded rather than fatal, and a header carrying a hostname is rejected before `InetAddress` sees
it, so a hostile header can't trigger a DNS lookup per auth request.

Tomcat's own `RemoteIpValve` (`server.forward-headers-strategy=native`) does the same job, and was
the alternative considered. It was passed over because it rewrites `getRemoteAddr()` at the
container level, where MockMvc never runs it — the three cases that matter (direct caller ignores
the header, trusted proxy honors it, spoofed header from an untrusted peer is discarded) would have
had no test coverage in a suite that starts no container.

**Residual exposure:** `docker-compose.yml` publishes the backend's `:8080` to the host, so with the
private ranges trusted, something *already inside* the private network could reach the backend
directly and spoof `X-Forwarded-For` to rotate its bucket key. That is strictly narrower than the
pre-#336 state (a free global lockout from anywhere on the internet) and the per-email bucket is
untouched either way. Closing it means not publishing that port in a deployment that faces anything
but localhost.

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

### Class layout (#319)

`SuggestionService` owns the cache and the order of the pipeline; each stage lives in a
package-private collaborator alongside it in `com.wewatch.api.service`. The collaborators are
constructed in the `SuggestionService` constructor rather than injected as beans, so the service
stays the single wiring point and its constructor stays the single thing tests and the tuning
harness build.

| Class | Responsibility |
|---|---|
| `SuggestionService` | Caffeine cache, `topPicks` / `recompute` / `evictForUser`, loads the inputs, runs the stages, records impressions |
| `SuggestionContext` | The per-compute bundle every stage reads: entries, cached rows, taste profile, provider context, the dedup set, recency weights, the day-seeded `Random` |
| `TasteProfileBuilder` → `TasteProfile` | Genre affinities, top keywords (`KeywordAffinity`), recurring people (`PersonAffinity`), top genres per medium, dominant medium |
| `ProviderContextResolver` → `ProviderContext` | The member-service union and region (#270), the member-service *intersection* (#322), and the availability badges attached to served titles |
| `CandidateScorer` | Taste-profile ranking: genre score, keyword/person/streamability cache boosts, day-seeded jitter |
| `ShelfFiller` | Ranked candidates → a shelf: cross-shelf dedup, recency demotion (#264), genre diversification (#265) |
| `FranchiseShelfBuilder` | `FRANCHISE` (#272) |
| `BothWatchShelfBuilder` | `BOTH_WATCH` (#322) — mixed TV + movie discover against the members' shared services |
| `SeedShelfBuilder` | `PER_SEED` (#232), `FINISHED_SEED` (#235), and the `MORE_PICKS` catch-all (#266) — one class because they share the leftover pool |
| `GenreShelfBuilder` | `GENRE_PROFILE`, one shelf per medium |
| `ExplorationShelfBuilder` | `NEW_RELEASES`, `HIDDEN_GEMS`, `TRENDING`, `PERSON`, `KEYWORD` and their daily rotation (#235) |
| `DiscoverPolicy` | The discover page depth, vote floor, and sort orders the genre and exploration builders must agree on |
| `TmdbPaging` | The day-seeded page draw's empty-deep-page fallback to page 1 (#249) |

**Stage order is behavior, not style.** The stages share two pieces of mutable state, and both
make the order they run in load-bearing:

- The **dedup set** (`SuggestionContext.seen`) is seeded with owned and dismissed titles and
  added to by every shelf as it fills, which is what keeps a title to at most one appearance
  across the whole set. Earlier stages therefore get first pick — franchise first (#272),
  both-watch second (#322), exploration last.
- The **day-seeded `Random`** is one shared stream. What each stage draws, and in what order,
  determines what every user sees. Reordering stages, adding a draw, or drawing before an early
  return that used to happen first (an empty person profile, no named keywords) silently changes
  every user's shelves without failing an obvious assertion. The offline tuning harness below is
  the check for this: it renders full shelf sets for the fixture watchlists, so a diff of its
  reports before and after a pipeline change is a byte-level no-behavior-change proof.

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

### Dismissals and thumbs-down vetoes

"Not interested" (#268, `suggestion_dismissals`, one row per `(user_id, tmdb_id)`) is permanent
and explicit-intent, unlike the impressions table's time-windowed, continuously-recorded
penalty above — it's undoable but doesn't decay. Dismissed ids seed the cross-shelf `seen` dedup
set before any shelf is built, so a dismissal excludes a title from every shelf kind at the one
point they all flow through, and a dismissed title can never re-enter via the thin-shelf
`seen.remove` release (that release only touches ids that actually made it into a shelf).
Dismissals are user-scoped like impressions (union over a shared list's members).

A **thumbs-down** (#273) now seeds `seen` the same way (#322,
`TitleRatingService.downRatedExternalIds`). It previously only steered the taste profile through
a negative `profileWeight`, which meant a title a member had explicitly rejected could still be
suggested back to the list — the gap only stayed invisible because a rated title is usually
already on the watchlist, and therefore already in `seen` as an owned id. The exclusion reaches
titles rated on *any* of the user's lists, since ratings are user-scoped.

Note the deliberate asymmetry with `TitleRatingService.effectiveRatings`, which still **nets** a
shared list's ratings (one member's UP and another's DOWN cancel to unrated). Blending what the
members *like* is a fair way to score candidates; there is no fair way to *show* someone a title
they said no to. So scoring nets, and exclusion vetoes — the same rule dismissals already
followed. Ratings are keyed on the internal `titles.id` while the dedup set works in TMDB
external ids, so `TitleRatingRepository.findDownRatedExternalIds` bridges the two with an
explicit entity join (there is no JPA association between `TitleRating` and `Title`).

### Specialty shelves

**Franchise continuation** (#272, `FRANCHISE`) is structurally different from every other
shelf: `FranchiseShelfBuilder` runs *before* the seed shelves, and not inside the daily-rotated
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
via `PATCH /api/users/{id}`). `ProviderContextResolver` resolves a `ProviderContext` from the
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

### What can we both watch (#322)

The provider context above answers *"can one of us stream this"* — a union. For a shared list
that's the wrong question. `BOTH_WATCH` answers the one a household actually opens WeWatch with:
what's on a service **all** of us have.

`ProviderContextResolver.resolveShared` intersects the members' provider ids into a second
`ProviderContext`, carried alongside the union one on `SuggestionContext`. TMDB's
`with_watch_providers` is an OR over the ids passed, so handing discover the *intersected* set is
exactly "streamable on a service all of you have" — the TMDB client needed no change, only a
different set. `BothWatchShelfBuilder` runs one discover per medium (TV and movie, mixed into a
single shelf — "what do we watch tonight" shouldn't have to pre-commit to a medium), ranks the
merged pool on the existing blended taste profile, and diversifies on fill.

The gate is strict: 2+ members, **every** one of them with a region and providers configured, a
single shared region, and a non-empty intersection. Anything else disables the shelf. The union
can safely ignore an unconfigured member because they add nothing to it; an intersection can't,
or the shelf would be promising a service nobody confirmed. An empty intersection is a real
answer ("nothing you can both stream") and the honest way to deliver it is to show no shelf, not
to quietly widen the filter.

Two consequences worth knowing:

- **Badges are context-dependent.** `attachBadges` takes both contexts and badges `BOTH_WATCH`
  from the intersection while every other shelf badges from the union — otherwise a both-watch
  tile could advertise a service only one member subscribes to, contradicting its own shelf.
- **The stage sits second, after franchise.** A remaining part of a series someone already
  started is still the more precise call, and a collection shelf barely competes for candidates;
  everything generic comes after. The frontend then *displays* it first
  (`SHELF_KIND_ORDER.BOTH_WATCH = -2`) — build order and display order have always been separate.

The builder draws nothing from the day-seeded `Random` when the gate fails, which is what let it
be inserted mid-pipeline without moving anyone's shelves: every tuning fixture that existed
before #322 is single-member, so the harness baseline came back byte-identical. The
`shared-household` fixture covers the qualifying case.

**Known limitation:** correctness rests on TMDB's discover filter, not on our own provider cache
— a candidate with no `tmdb_title_cache` row still belongs on the shelf (TMDB said it's
streamable there) but will render without badges until the cache warms.

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

## Returning This Week (#321)

`GET /api/watchlists/{watchlistId}/returning?days=7` (`ReturningEpisodeController` →
`ReturningEpisodeService`) lists the watchlist's `WATCHING` TV entries whose next episode airs
within the window, soonest first, one row per show. It reads only `tmdb_episode_cache`, so it
adds no TMDB traffic per page load. The frontend renders it as a "Returning this week" panel on
Home, ahead of "Continue watching".

**Why not reuse `episodeProgress.nextAirDate`.** Every library entry already carries a
`nextAirDate` from `EpisodeProgressSummaryService`, which makes the whole feature look like a
client-side filter over data already in hand. It isn't. That field is the air date of the next
*unwatched* episode — for anyone behind on a returning show it is a date in the **past**, so
filtering on it would hide exactly the shows the feature exists to surface. The query here asks
a different question of the same table: what airs next, regardless of what you have seen. The
two fields look interchangeable and are not; don't collapse them.

**The window lives in Java, not in SQL.** The native query takes explicit `from`/`to` bounds
(`BETWEEN`, inclusive at both ends — an episode airing today and one airing exactly seven days
out both count, and null air dates drop out for free) and returns *every* matching episode; the
service computes the bounds from the injected `Clock` and keeps the first row per entry. The
obvious alternative — a `ROW_NUMBER()` CTE doing the per-show pick in SQL, as
`EpisodeProgressRepository.findNextEpisodeByEntryIds` does — was rejected because **no test in
this project executes SQL**: there is no `@DataJpaTest`, no Testcontainers, no `@SpringBootTest`,
and every repository is mocked. Logic pushed into that CTE would be logic no test could reach,
and the issue's acceptance criteria are specifically about window boundaries. Keeping the bounds
and the pick in Java makes them assertable from `ReturningEpisodeServiceTest` with a fixed
`Clock`. The SQL itself is still only verified by hand against local Postgres — same standard
`findNextEpisodeByEntryIds` is held to. If a DB-integration layer is ever added, this is the
first query that should get one.

`V23` adds `idx_tmdb_episode_cache_air_date`; the table's only prior index was the unique
`(tmdb_id, season_number, episode_number)`, useless for a date-range predicate.

### Nightly episode-cache refresh

`EpisodeCacheRefreshJob` (`@Scheduled`, `app.episode-cache.refresh-cron`, default 03:30 daily)
re-prewarms every TV show someone has in `WATCHING` (`TitleRepository.findWatchingTvExternalIds`).
This is not an optimization — it is what makes the feature *true*. The cache is otherwise written
only when a title is added, at startup backfill, or when someone opens a season, so a season
announced after a show's last prewarm is simply **absent from the cache**, and "Returning this
week" would confidently report nothing for precisely the show that just came back. The issue's
"no extra TMDB traffic" criterion forbids traffic *per page load*, which a nightly job is not.

Cost is `1 + seasons` TMDB calls per watching show, once a night — tens of titles for a
household, trivially inside TMDB's limits. It reuses `TmdbCacheService.prewarmShow`, which is
already `@Async` and already swallows per-show failures, so one unreachable show doesn't cost the
rest of the run. `@EnableScheduling` had to be added to `WewatchApiApplication` (only
`@EnableAsync` was present); without it `@Scheduled` is silently inert. Set the cron to `-` to
disable — worth doing in local dev, where the job just burns TMDB quota.

Single-instance: every node would run its own refresh. Harmless today (the writes are idempotent
upserts), but it becomes duplicate TMDB traffic under horizontal scaling — move to a leader-elected
or externally-triggered job before adding a second instance.

## Stats (#323)

`GET /api/watchlists/{id}/stats` → `StatsController` → `StatsService`. Counts, watch time and a
genre breakdown for one watchlist.

**Scoped to the watchlist, not the caller.** `watchlist_entries` is keyed on watchlist, and
`episode_progress` has no `user_id` — a progress row belongs to an *entry*, so on a shared list two
members write to the same row. "My stats" is therefore not representable today, and pretending
otherwise (a `/api/stats` union over the caller's watchlists) would have dressed the household's
numbers up as one person's while also double-counting any title sitting on two lists. The page
follows the watchlist switcher instead, like Home and Discover. Per-member attribution needs a
`user_id` on `episode_progress` first.

### The two things the data model didn't have

The issue assumed "runtimes and cached genre metadata are all already stored". Two-thirds true:

- **Movie runtime was not stored anywhere.** `TmdbMovieDetail.runtime` was fetched on every movie
  detail call and discarded — `upsertMovieCache` never persisted it. V24 adds
  `tmdb_title_cache.runtime_minutes`.
- **Genre *names* were not stored.** Titles cache bare TMDB genre ids (`genre_ids`); the names
  existed only transiently on live detail calls, and `TmdbClient` had no genre-list method.

⚠️ **A new column on `tmdb_title_cache` does not fill itself for movies.** TV rows are TTL-refreshed
on read (`getSeasons`/`getSeasonDetail`), so a new TV field backfills for free — which is how
`keywords` and `watch_providers` were introduced. **Movie rows have no such path: nothing ever
re-fetches them.** `TmdbCacheBackfill` only prewarmed titles with *no cache row at all*, so every
already-cached movie would have kept `runtime_minutes = NULL` forever and the headline watch time
would have silently been TV-only. Hence the startup pass over
`TmdbTitleCacheRepository.findMovieIdsMissingRuntime()`, which re-prewarms those rows and is
self-limiting once they're populated. Any future *movie* field on this table needs the same
treatment — don't assume the TTL will save you.

`GenreCatalogService` resolves ids to names: Caffeine, 24h TTL, modelled on `WatchProviderService`
(same shape, same reasoning — a ~40-entry list that changes on the order of years). The movie and TV
catalogs merge into one flat map; their ids overlap only where the names agree (16 Animation, 18
Drama, 35 Comedy), and TV's own ids (10759 "Action & Adventure", 10765 "Sci-Fi & Fantasy") carry
their own names, so callers never need to know a title's medium to label it. A TMDB outage returns
an empty map rather than throwing — the numbers all come from our own tables, so losing the genre
bars must not cost the page its stats. The mapping function returns `null` on failure so Caffeine
records nothing and retries, rather than caching the outage for the whole TTL window.

### What counts

Entry status and episode progress answer different questions, and the split is deliberate:

- **Movies / shows finished** come from entry status (`WATCHED`).
- **Episodes watched** come from `episode_progress`, *regardless of entry status*. Being 40 episodes
  into a show you're still `WATCHING` is 40 episodes watched — and in practice this matters more
  than it sounds: the dev database has a show with 155 watched episodes sitting in `WANT_TO_WATCH`.
- A show flipped straight to `WATCHED` without ticking episodes writes no progress rows, so it
  counts as a show finished and adds no time. That is honest, not a bug: we have nothing to sum.

⚠️ **`findWatchedEpisodeRuntimes` joins the episode cache with a LEFT JOIN on purpose.** An episode
that's been ticked but has no cached row still yields a row, with a null runtime — so it counts
toward "episodes finished" and contributes nothing to the time. An inner join would drop it from
both, under-reporting the number users are proudest of. `itemsMissingRuntime` reports how many such
items there are, which is what makes the watch time legible as a floor rather than a fact.

**Genre breakdown is watch time, and a title counts in every genre it carries.** So the parts sum to
more than `totalMinutes` — it is a shape, not a partition. That is why the response is minutes and
the bars are scaled against the top genre: a percentage-of-total bar would imply a sum-to-100 that
isn't true, and would render >100% widths. The page says so in a footnote rather than hiding it.
Attributing by watch time (rather than by title count) also means an unfinished 60-episode show
shows up for what it is: 40 hours of drama is 40 hours of drama whether or not you finished it.

### Testing

All aggregation lives in `StatsService`, not in SQL — the same call `ReturningEpisodeService` makes,
for the same reason: **no test in this suite executes SQL** (every repository is mocked; there is no
`@DataJpaTest`/Testcontainers anywhere), so a `SUM`/`GROUP BY` in the query would be arithmetic
nothing could check. The two native queries are dumb row fetches, verified by hand against local
Postgres. `TmdbCacheBackfillTest` exists specifically because the runtime backfill is the kind of
thing that can silently do nothing and take every movie's watch time to zero with it.

## PWA Manifest (#324)

WeWatch is installable to the home screen: `frontend/public/manifest.webmanifest`, five PNG icons,
and a handful of `<head>` tags. **Installability only — there is no service worker**, so no offline
support and no cache-invalidation surface. Static files in `public/` need no build, Docker, or Vite
config change: Vite copies `publicDir` into `dist/`, the Dockerfile's `COPY . .` picks it up, and
nginx's `try_files $uri ...` serves real files ahead of the SPA fallback.

### The icons are generated, not drawn

The repo had **no image assets at all** — the only brand mark was `WeWatchLogo.tsx`, a 520×140
inline-SVG wordmark that cannot survive being squeezed into a square tile. So the app icon is a new
mark: a geometric "W" in brand teal `#22B6B0` on the app background `#0f1720`, stroked with round
caps and joins to echo the wordmark's Nunito 800 weight. **It is paths, not `<text>`** — a webfont
would not resolve in a headless renderer, and glyph rendering would drift between machines.

`frontend/public/favicon.svg` is the authoritative vector. `scripts/render-icons.sh` rasterises it
(and the two variants in `scripts/`) through headless Chrome — nothing else on a stock macOS box
rasterises SVG. The PNGs are committed; rerun the script only when a source SVG changes.

Three variants exist because three platforms crop differently: the `any` icons keep the rounded
tile; the `maskable` ones are full-bleed with the mark scaled to 0.78 so it survives Android's
circle/squircle crop (content must sit inside a centred circle of radius 40%); `apple-touch-icon.png`
is square and **opaque**, because iOS ignores manifest icons entirely and paints transparency black.

⚠️ **nginx does not know what a `.webmanifest` is.** Stock `mime.types` (checked on nginx 1.31.2)
has no mapping for the extension, so the manifest was served as `application/octet-stream`. The
exact-match `location = /manifest.webmanifest` in `nginx.conf.template` fixes the type — and, as a
bonus, keeps the manifest out of the SPA fallback. The `types` block is scoped to that location, so
it does not shadow the global type map (a `types` block *replaces* the map in its context — putting
one in `server` would have turned every PNG and JS bundle into octet-stream).

⚠️ **A missing static asset does not 404 — it returns 200 and a page of HTML.** `try_files $uri $uri/
/index.html` means a typo'd icon path is answered with `index.html`, which the browser then tries to
decode as an image. This is not hypothetical: `index.html` linked `/favicon.svg` from the first
commit and **no such file ever existed**; it went unnoticed for months precisely because the failure
is silent. `src/manifest.test.ts` now fails CI if any root-relative `href`/`src` in `index.html`, or
any icon in the manifest, does not exist on disk — and checks each PNG's real dimensions (read from
the IHDR header) against the size the manifest advertises. That file is `exclude`d from
`tsconfig.app.json` and compiled by `tsconfig.node.json` instead: it uses `node:fs`, and letting
`node` types into the app project is what would let app code reach for `process`, which Vite does not
polyfill in the browser.

### Standalone mode

`start_url` is `/`, not `/home`: the index route already redirects, and `/` is the one entry point
that stays correct whether or not the caller is signed in (every route but `/sign-in` is auth-gated,
so a signed-out launch correctly lands on sign-in). `theme_color` and `background_color` are both the
app background `#0f1720` — the app is dark-mode only (no `prefers-color-scheme` anywhere in
`index.css`), so one fixed pair is safe and the status bar reads as seamless. `orientation` is
deliberately unset; forcing portrait would be wrong on a tablet.

⚠️ **`env(safe-area-inset-*)` is currently inert, and that is the intended state.** `index.css`
already pads the mobile header (top) and mobile nav (bottom) for the notch and home indicator — but
those `env()` values resolve to **0** without `viewport-fit=cover` on the viewport meta, which is not
set. Without it, iOS insets the standalone webview itself and paints the strips with `theme_color`,
so nothing is ever obscured and no existing layout can break. If anyone adds `viewport-fit=cover` to
go edge-to-edge, those two paddings switch on and start doing real work — and the *left/right* insets
(landscape) are handled nowhere. That change needs testing on a physical device, not in Chrome's
device toolbar. `apple-mobile-web-app-status-bar-style` is omitted for the same reason:
`black-translucent` pushes content under the status bar and would require exactly that change.

⚠️ **An installed iOS PWA gets its own storage partition.** `localStorage` — where `AuthContext`
seeds the JWT for the #308 cold-load path — does not carry over from Safari, so each member signs in
once more inside the installed app. Expected, not a regression. Everything else about #242
tab-restore and #308 deep-linking is unaffected: same origin, same `BrowserRouter`, no `basename`.
