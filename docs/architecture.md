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

- `JwtTokenService` signs and decodes tokens with HMAC-SHA256 using the `JWT_SECRET` env var, which
  it validates at startup — see [Secret validation & issuer pinning](#secret-validation--issuer-pinning-346).
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

### Secret validation & issuer pinning (#346)

`JwtTokenService`'s constructor rejects a `JWT_SECRET` shorter than 32 bytes (256 bits, the HS256
minimum) with an `IllegalArgumentException`, which Spring surfaces as a failed context refresh — the
app refuses to start rather than booting green and throwing a 500 from `MACSigner` at the first
sign-in, which is what a short secret used to do. The bytes come from an explicit
`StandardCharsets.UTF_8` rather than the platform default. `jwtDecoder()` installs
`JwtValidators.createDefaultWithIssuer("wewatch")`, so the decoder checks `iss` on top of the
default timestamp validators; the issuer is a single `ISSUER` constant shared by the signer and the
validator so the two cannot drift.

⚠️ **The charset pin is only safe because the secret is ASCII.** `openssl rand -hex 32` (what the
README prescribes) emits hex, so UTF-8 and the old platform-default encoding produce identical key
bytes and no live token is invalidated. A secret containing non-ASCII would change key material
under this change and sign every existing session out. Same for the issuer pin: every token the app
has ever issued already carries `iss: wewatch`, so nothing in a user's localStorage is rejected by
it.

⚠️ **A startup assertion is invisible to this test suite.** There is no `@SpringBootTest` in
`backend/src/test` — every controller test is `@WebMvcTest` with `JwtTokenService` mocked, so the
constructor never runs and no test would have caught a regression here. `JwtTokenServiceTest`
constructs the service directly (31 bytes throws, exactly 32 is accepted) for that reason. The
issuer check is likewise covered by hand-signing a token with the *right key* and a foreign `iss` —
without that, the `setJwtValidator` line could be deleted with a green suite.

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
| `ExplorationShelfBuilder` | `NEW_RELEASES`, `TRENDING`, `PERSON`, `KEYWORD` and their daily rotation (#235) |
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
  `FINISHED_SEED`, `TRENDING`. Discover-backed shelves (`GENRE_PROFILE`, `NEW_RELEASES`)
  are exempt: they're already filtered to the user's top genres by construction,
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
day-rotated exploration slot alongside `TRENDING` and `NEW_RELEASES`, capped at
`MAX_EXPLORATION_SHELVES = 2` per compute (#235) — a kind that can't fill yields its slot to the
next. Both are exempt from genre diversification, matching `FRANCHISE`, since their theme (the
keyword or the person) is the shelf's coherence axis rather than genre mix. `KEYWORD` picks one
named keyword from the profile via the day-seeded rng and discovers a shallow page (1–3) of
either media type (whichever the profile leans toward) — single-keyword catalogs thin fast, so
rotation comes from the keyword draw, not page depth; it's discover-backed and therefore takes
the watch-provider filter below. `PERSON` is movie-only (TMDB's TV discover has no `with_people`
filter), always page 1 since filmographies are shallow, and labels itself "Directed by X" when
the person directs at least as often as they act, "More with X" otherwise.

**The rotating `HIDDEN_GEMS` kind was removed in #375**, taking the rotation from five kinds to
four. It was a genre-filtered `vote_average.desc` discover query on a page drawn from a mid-deep
band [4, 18], handed straight to `filler.fill` with no taste ranking — "obscure" approximated by
page depth, never reading TMDB's `popularity` at all. `ShelfKind.HIDDEN_GEMS` stays on the enum
and no builder emits it until #376 gives the name to a properly scored shelf.

⚠️ **The removal is deliberately not byte-identical in the tuning output.**
`Collections.shuffle(order, ctx.rng())` consumes `size - 1` draws, so a four-element shuffle takes
3 where five took 4 — a different daily permutation for every user — and on days `HIDDEN_GEMS` used
to win the 2-of-5 lottery its page draw disappears and a different kind fills the slot. The
invariant that *was* checked: the diff touches exploration shelves and nothing else. Franchise,
both-watch, seed, genre, and the pooled catch-all all build before `explorationShelves.build(ctx)`
in `SuggestionService.compute`, so they take their rng draws first and cannot be perturbed by
anything downstream. Zero non-exploration shelves moved across all three kind-bearing reports.

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

When the docs are enabled, `SecurityConfig` permits `/v3/api-docs/**`, `/v3/api-docs.yaml` (a
sibling path the `/**` pattern does not match), `/swagger-ui/**`, and `/swagger-ui.html` alongside
the existing `/api/health` and `/api/auth/**` matchers — otherwise `anyRequest().authenticated()`
would block Swagger UI itself before a caller could authenticate. The docs endpoints are
unauthenticated *when enabled* by necessity: a caller needs the spec before they have a token.
Swagger UI is served directly from the backend, not proxied through the Vite dev server, so no
CORS changes were needed. Its webjar assets are served under `/swagger-ui/**` (via
webjars-locator-lite), so no extra matcher is needed for them.

**Opt-in exposure (#297, hardened by #343):** springdoc is disabled in the base
`application.properties` (`springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` = `false`)
and switched on only by the `local` profile; `application-prod.properties` keeps an explicit
`false` pair as the record of the prod decision. #297 originally disabled the docs in the prod
profile only, which failed open — springdoc's own default is *enabled*, so any future profile, or
a deployment that forgot to set a profile at all, would have published the full API surface
(every route, DTO field, and error code) anonymously, and only the one prod file remembered to
say no. The API is allowlist-gated and the docs' only consumer is the first-party frontend
developer, so there is no benefit to offset that exposure.

`SecurityConfig` reads the same `springdoc.api-docs.enabled` key (with a fail-closed `:false`
default) to decide whether the docs matchers are `permitAll` at all — reusing springdoc's own
switch rather than minting an `app.docs.*` one means the security rule cannot drift apart from
whether the docs beans actually register. Gating on `api-docs.enabled` alone is deliberate:
Swagger UI is useless without the spec, so a config that enables the UI but not the spec gets
401s on the UI paths, which is fine. With the matchers gone the paths fall through to
`anyRequest().authenticated()` and answer 401 rather than 404 — prod no longer confirms the paths
are unmapped, and (because springdoc's controllers never load under `@WebMvcTest`) it is also
what makes the gate assertable in the security slice: `OpenApiDisabledByDefaultTest` pins the
no-profile default (the only test in the suite without `@ActiveProfiles`, on purpose — a typo in
the base properties would reopen the docs without failing any other test), `OpenApiProdSecurityTest`
pins the prod profile, and `OpenApiSecurityTest` remains the dev-side expectation. See
[`docs/api.md`](api.md) for how to reach Swagger UI locally.

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

---

## Security Headers (#337)

`frontend/nginx.conf.template` previously sent no security headers at all. The one that earns its
keep here is the **Content-Security-Policy**: the WeWatch JWT lives in `localStorage` and is a 24h
stateless token with no revocation (the accepted trade-off in "Session lifetime & hardening (#293)"),
so an XSS foothold would hand an attacker a day-long session nobody can kill. There is no known XSS
sink today — no `dangerouslySetInnerHTML` anywhere, React escaping throughout — so this is
defense-in-depth for a trade-off already made, not a fix for a live hole.

Alongside it: `X-Content-Type-Options: nosniff`, `frame-ancestors 'none'` (plus `X-Frame-Options:
DENY` for browsers predating it), `Referrer-Policy: strict-origin-when-cross-origin`, and HSTS.

### The CSP is an inventory of what the app actually loads

Every source in the policy is there because something breaks without it. The list is longer than it
looks like it should be, because two of the origins are easy to forget:

| Source | Directive | Why |
|---|---|---|
| `accounts.google.com/gsi/client` | `script-src` | the GIS script tag in `index.html` |
| `accounts.google.com/gsi/` | `frame-src`, `connect-src` | the Google button is an iframe, and it makes its own calls |
| `accounts.google.com/gsi/style` | `style-src` | styles GIS injects into our page |
| `fonts.googleapis.com` | `style-src` | the Nunito stylesheet, linked from `index.html` |
| `fonts.gstatic.com` | `font-src` | the font files that stylesheet then pulls |
| `image.tmdb.org` | `img-src` | every poster, still, cast photo and provider logo — `TmdbClient` hands the frontend absolute URLs |
| `data:` | `img-src` | the inline chevron SVG background in `index.css` |
| `'self'` | `connect-src` | the API: `services/api.ts` uses `const BASE_URL = '/api'`, same origin via the nginx proxy |

Google Fonts is the one most likely to be missed — the issue that filed this work didn't list it, and
omitting it doesn't error loudly, it just silently renders the app in a fallback font.

⚠️ **`style-src` needs `'unsafe-inline'` and cannot be rescued.** CSP governs inline *style
attributes*, and `WeWatchLogo`, `ShowDetailPage` (episode progress bar) and `StatsPage` (genre bars)
set `style={{ width: `${pct}%` }}` — a value computed at render time, so it can be neither hashed nor
nonced (nonces don't apply to attributes at all). The exposure is CSS injection, not script
execution. **`script-src` stays strict** and that is the directive doing the real work: Vite emits an
external module bundle and the built `dist/index.html` contains no inline script, so no
`'unsafe-inline'` or `'unsafe-eval'` is needed there. Keep it that way — a test asserts it.

### Two traps in the nginx config itself

⚠️ **`add_header` in a `location` block silently drops every header declared in `server`.** nginx
*replaces* the inherited set rather than merging with it, the moment a location adds one of its own.
All five headers therefore live in `server`. `location = /manifest.webmanifest` is safe as written
(a `types` block doesn't reset headers), but adding, say, an `add_header Cache-Control` there would
strip the CSP from the manifest response and nothing would fail visibly. `src/security-headers.test.ts`
fails CI if any location block declares an `add_header`. All headers carry `always` so they also ride
on error responses, not just 2xx/3xx.

⚠️ **HSTS is gated on `X-Forwarded-Proto`, not `$scheme`.** Railway terminates TLS at its edge and
forwards plain HTTP to the container, so `$scheme` is *always* `http` here and would never fire. An
http-context `map` sets `$hsts_header` only when the edge reports the browser hop was HTTPS; nginx
omits an `add_header` whose value is empty, so a local `docker compose` run on `http://localhost:3000`
sends no HSTS at all. This survives `envsubst` only because the Dockerfile passes an explicit variable
list (`envsubst '$BACKEND_URL'`) — dropping that list would blank `$hsts_header` and
`$http_x_forwarded_proto` and emit an empty header. The coupling is invisible from the template, so
it is commented in both files.

The `/api/` location adds `proxy_hide_header` for `X-Content-Type-Options` and `X-Frame-Options`:
Spring Security sends both by default (verified against the running backend — 401s carry them), and
`add_header` appends rather than replaces, so without this every API response would ship each header
twice. `proxy_hide_header` does *not* reset `add_header` inheritance — only a nested `add_header` does.

### Testing

`src/security-headers.test.ts` is the second file-on-disk test after `manifest.test.ts` (#324), and
registered the same way — `exclude`d from `tsconfig.app.json`, compiled by `tsconfig.node.json`,
because it reads `node:fs`. It exists because **a blocked resource fails only in the built container**:
`npm run dev` serves no CSP at all, so a new third-party `<script>` or `<link>` in `index.html` would
sail through CI and every local run, then break in production. The test cross-checks `index.html`'s
external origins against the policy, and pins the directives an XSS foothold would need
(`default-src`/`frame-ancestors`/`object-src`/`base-uri`, and no `'unsafe-*'` in `script-src`).

The template itself is never executed by any test, so the policy was verified against the real
container: headers present on `/`, on an SPA-fallback route, on `/manifest.webmanifest` and on a
missing asset; HSTS absent over plain http and present with `X-Forwarded-Proto: https`; exactly one
copy of each header on a proxied `/api/` response. Then headless Chrome against the running container
with the real client id — the sign-in page (GIS script, its button iframe, Google Fonts) produced zero
CSP violations, and a probe page on the same origin confirmed the remaining directives: a TMDB poster
loads, a `/api` fetch reaches the backend, and an inline `style` width applies.

## Trailer Links (#340)

`GET /api/titles/detail` carries a nullable `trailerUrl` — a ready-to-open
`https://www.youtube.com/watch?v=<key>` — and both detail pages (`TitleDetailPage`, and
`ShowDetailPage` for a library TV entry, which already calls the same endpoint) render a
"▶ Watch trailer" link when it is non-null. Like `posterUrl`, the backend hands over a finished URL
and the frontend stays dumb.

**The data is free.** `videos` joins `credits` (#269) and `watch/providers` (#270) on the existing
`append_to_response` of `getMovieDetail` / `getTvDetail` — same request, bigger payload. No
`tmdb_title_cache` column, no migration, no backfill: title detail is served live from `TmdbClient`,
so none of the #323 movie-rows-never-refresh problem applies.

**Link out, don't embed.** An inline `youtube-nocookie.com` iframe would need a `frame-src` carve-out
in the #337 CSP and would pull YouTube's player JS and tracking into every detail view. A plain
`youtube.com/watch` link needs **no CSP change at all** — the policy sets no `navigate-to`, so
top-level link navigation is ungoverned — and on mobile it hands off to the YouTube app, which is
what the PWA (#324, no service worker, no in-app player) wants anyway. Do not add `frame-src` or an
`i.ytimg.com` `img-src` entry unless someone actually builds an embed or a thumbnail.

**Trailer buttons on `TitleCard` tiles are out of scope** for the same reason: a tile has no live
detail call behind it, so the video key would have to be cached, which reintroduces the #323 trap.

⚠️ **The pick lives in `TrailerPicker`, not in a private `TitleController` helper.** It is the only
real logic in the feature, and a private controller method is reachable only through
`@WebMvcTest`/`MockMvc` — a clumsy way to assert a dozen selection cases. Same reasoning as the #321
"keep the date window in Java so a test can see it" note. `TrailerPickerTest` covers the ranking, the
site and type filters, the tiebreak, and every null shape.

⚠️ **"Most recent" alone picks the wrong video.** TMDB returns a large mixed bag — 29 videos on The
Matrix at time of writing — and the *newest* entries are Featurettes and Behind-the-Scenes clips
(the 2026 ones postdate every trailer by years). So the type filter is what does the work: only
`Trailer` and `Teaser` on `site == "YouTube"` survive, ranked official-trailer > trailer >
official-teaser > teaser, and only then does the most recent `published_at` break the tie. Dropping
the type filter and taking the newest video would have linked The Matrix to a featurette.

`published_at` is compared as a raw ISO-8601 string — lexicographic order on that format *is*
chronological, and an undated video sorts below a dated one, which is the behavior we want anyway.

**Verified end-to-end against live TMDB and a running backend** (the pick logic is the part mocks
cannot validate): The Matrix resolves to its official 25th-anniversary trailer, Game of Thrones to
its show trailer, and a title whose `videos.results` is empty (TMDB 55555) returns `trailerUrl: null`
so no link renders.

## Authorization before lookup in addMember (#344)

`WatchlistController.addMember` resolved the target email *before* any authorization ran, and the
lookup's failure mode was observable: an unregistered address threw `NoSuchElementException` → 404
(echoing the address back), a registered one fell through to `addMember`'s `requireOwner` → 403, or
to 409 if already a member. Status alone told an unauthorized caller whether an address was
registered. The fix hoists `watchlistService.requireOwner(watchlistId, caller.getId())` above the
`findByEmail`, and `UserService.findByEmail` no longer reflects the probed address
("No user is registered with that email").

**The check is deliberately duplicated.** `WatchlistService.addMember` still calls `requireOwner`
itself. The controller's call is the ordering fix; the service's is the invariant, and it must stay
so that any future caller is safe by construction. The cost is one extra membership read on a rare,
non-hot path.

⚠️ **The fix could not live in `WatchlistService`.** The natural shape — `addMemberByEmail(watchlistId,
email, caller)` — is impossible: `UserService` already injects `WatchlistService` (it provisions the
personal watchlist on user creation), so the reverse dependency is a cycle. The controller is the only
seam where both services are in scope, which is *why* the ordering was expressible as a bug in the
first place.

⚠️ **This narrows the oracle; it does not close it.** Every user owns an auto-provisioned PERSONAL
watchlist, and `addMember` has no watchlist-type guard (the only `WatchlistType.PERSONAL` check in the
backend is on `delete`). So an authenticated attacker can still enumerate by probing against a
watchlist they legitimately own — they now merely have to use their own list instead of anyone's. What
actually bounds the exposure is that sign-in is **allowlist-gated** (#318), so the enumerable
population is a small, already-known set of addresses; the per-IP/per-email throttle raises the cost of
sweeping it. Closing the oracle properly means making the response indistinguishable for registered and
unregistered targets — i.e. invitation semantics, where adding an unknown address succeeds and sends an
invite rather than 404ing. That is a feature, not a bug fix, and is not in #344. **If WeWatch ever drops
the allowlist, build that before opening sign-up.**

`WatchlistControllerTest` covers the AC path (non-owner + unregistered email → 403, not 404) and
asserts `findByEmail` is never reached; the old ordering fails both new assertions, which is the point
of them.

## Canonical email casing (#345)

Email is the identity key at every boundary — registration, sign-in, provider linking, the allowlist,
watchlist member-add — and each layer used to compare it differently. The allowlist read was
`existsByEmailIgnoreCase`, but `UserRepository.findByEmail` was exact-match and `uq_users_email` (V1)
was a case-sensitive `UNIQUE`. So `Foo@x.com` and `foo@x.com` could become two accounts for one human:
the duplicate check missed the variant and the constraint happily accepted it. Which account a Google
sign-in linked to then depended on the exact casing Google returned.

The fix is one canonical form — trimmed, `toLowerCase(Locale.ROOT)` — in `security/EmailNormalizer`,
applied on write and matched case-insensitively on read, with the database as the backstop.

**Two layers, on purpose.** `UserService` normalizes every write (`create`, `update`,
`registerWithPassword`, `findOrCreateByProviderIdentity`) and reads via `findByEmailIgnoreCase`; V25
replaces the case-sensitive constraint with `UNIQUE (lower(email))`. The index is what makes the bug
*impossible* rather than merely unlikely — a case-variant insert that bypasses the service now fails
loudly with a constraint violation instead of silently minting a second account. Reads stay
`IgnoreCase` rather than "exact match on an already-normalized string" so a row that escaped
normalization (the `allowed_emails` rows are inserted by hand via psql, so hand-edited `users` rows
are plausible too) still resolves; Postgres serves it from the same functional index, so it is not a
scan.

⚠️ **`Locale.ROOT` is load-bearing.** Under a Turkish default locale `"I".toLowerCase()` is the
dotless `ı`, which would key an ASCII address to a non-ASCII string — a JVM-locale-dependent identity.
`EmailNormalizerTest` sets the default locale to `tr` to pin it; nothing else in the suite would catch
its removal.

⚠️ **`provider_id` is a second copy of the address.** `registerWithPassword` stores the email in
`provider_id` as well (password users key on `("email", <address>)`). Normalizing only the `email`
column would strand `findByProviderAndProviderId` against it, so both the write path and V25's
backfill canonicalize it.

⚠️ **The migration refuses to merge; the allowlist dedupe is safe.** Two `users` rows differing only
by case are two humans' watchlists, ratings and episode progress — V25 raises with the colliding
addresses and lets Flyway abort, so a person decides. `allowed_emails` gets the opposite treatment
(`DELETE ... WHERE a.id > b.id`): an allowlist row owns no data, so dropping the later duplicate is
lossless. Its `UNIQUE` was case-sensitive too, so two rows for one human could coexist there as well.

⚠️ **The sign-in credential is the one email entry point with no bean validation.** It arrives as a
JSON *string* inside `TokenRequest.credential` and is parsed by hand, so unlike `RegisterRequest` /
`AddMemberRequest` it never passes `@Email` — which means it is the only path that can carry
surrounding whitespace. The allowlist gate runs first and `existsByEmailIgnoreCase` folds case but not
whitespace, so an untrimmed address was answered **403 "not allowed"** — rejected by its own allowlist
row. Hence `parseEmailCredential` canonicalizes at the edge and `requireAllowedEmail` normalizes before
the lookup, so the throttle bucket, the allowlist gate and the user lookup all key off one string.
Found by driving the real flow, not by the unit tests.

**Entity annotations were removed, not updated.** JPA cannot express a functional index, so a
case-sensitive `unique = true` / `@UniqueConstraint` on `User.email` would describe a constraint the
schema no longer has (they were inert anyway — `ddl-auto=none`, Flyway owns the schema). `UserTest`
now pins that they stay *off*, and `UserEmailUniquenessTest` reads the shipped V25 file off the
classpath and asserts its shape — the same trick `ClientIpResolverTest` uses on the shipped
`trusted-proxies` default. Without it V25 could be deleted with a green build: **no test in
`backend/src/test` executes SQL** (no `@DataJpaTest`, no Testcontainers), so the migration itself is
verified by hand against local Postgres.

**Deploy order does not matter.** Reads are case-insensitive, so the new code tolerates
pre-migration mixed-case rows; the migration is a no-op on already-canonical ones. And nothing on the
authenticated request path looks a user up by email — `JwtTokenService` puts the user id in the
subject and `email` is an informational claim — so normalizing storage cannot invalidate live tokens.

**Relationship to #342.** This makes the `findOrCreateByProviderIdentity` link-by-email fallback
*casing-stable*; it does not decide whether that fallback should link at all. It slightly widens the
fallback's reach (it now matches case variants), which is the intent here and exactly why the
pre-hijack fix (#342) should land on top of it.

## Account linking & email immutability (#342)

Two unverified-ownership behaviors composed into a pre-hijack: `PATCH /api/users/{id}` let a user
change their email to any *allowlisted* address with no proof of ownership, and
`findOrCreateByProviderIdentity` fell back to `findByEmailIgnoreCase` on a first Google sign-in and
adopted whatever account it found — overwriting `provider`/`provider_id` while leaving the existing
`password_hash` in place. Claim a household member's address before they sign up, wait for them to
sign in with Google, and you are both on one account: they use it, and you still hold its password.

**The claim vector that mattered was not the one in the title.** `POST /api/auth/register` is
unauthenticated and gated *only* by the allowlist, so anyone who knows an allowlisted address can
already claim it by registering a password account for it — no `PATCH` required. Removing the email
change therefore does not close the pre-hijack; **the guard at the link sink is the whole fix.** Both
shipped, for different reasons.

### The link fallback now refuses accounts that hold credentials

`UserService.findOrCreateByProviderIdentity` throws `AccountLinkConflictException` (→ **409**, next to
`DuplicateEmailException` in `ApiExceptionHandler`) when the email-matched row has a `passwordHash` or
*any* `providerId`. That leaves exactly one adoptable case: a row with **no password hash and no
provider identity** — one nobody can sign into today, so linking it grants no one access they did not
already have. (`userService.create()` has no callers in `src/main`; in practice this is V1-era/seeded
rows, and keeping the branch is what stops the change from stranding them.)

Already-linked accounts never reach the fallback — they hit on `(provider, provider_id)`. The only
behavior that changes is a *first* Google sign-in against an account that already has a password: it
now 409s, telling the user to sign in with the password they already have, instead of silently
merging two identities.

⚠️ **Clearing `password_hash` on link — the other option the issue floated — is not safe.** It locks
the squatter out of the password but hands the victim an account the squatter *seeded*: `addMember`
has no `PERSONAL` type guard (see "Authorization before lookup in addMember (#344)"), so the squatter
can already be a member of that account's watchlist and keeps read/write access after the link. Refuse
the link; do not try to launder the account.

⚠️ **The conflict must not feed the auth throttle.** `AuthController.exchangeToken` records an IP
failure for `InvalidCredentialException | RegistrationNotAllowedException` — `AccountLinkConflictException`
is deliberately **not** in that `catch`. It is a legitimate user hitting a wall, not a credential
guess; counting it would let a confused household member lock their own IP out of sign-in via the
#318/#336 buckets. `repeatedLinkConflictsDoNotThrottleTheClient` pins this (the slice sets
`ip-max-attempts=4`, so a counted failure would turn the 5th attempt into a 429).

This adds no enumeration oracle: reaching the branch at all requires a Google-verified credential for
the address (`GoogleTokenValidator` checks `email_verified`), so the caller already owns it.

### Email is now immutable identity

`UserUpdateRequest` has no `email` field, `UserService.update` takes `(id, displayName)`, and
`UserController` lost the allowlist check and its `AllowedEmailRepository` dependency. The frontend
never sent `email` — `updateStreamingSettings` is the endpoint's only caller — so no UI changed.
A body still carrying `"email"` is *ignored*, not rejected (unknown properties are not fatal), which
fails closed; `updateUserIgnoresEmailInBody` pins that there is no path from the request to an email
write. Changing an address is now an admin/DB operation.

Bonus: this retires the #345 trap where `provider_id` — a second copy of the address for password
users — went stale after an email change. The two copies can no longer diverge.

⚠️ **The enumeration/squatting exposure is narrowed, not closed, and the bound is the allowlist.**
Anyone allowlisted can still register a password account for another allowlisted address they don't
own; they simply can no longer *inherit that person's Google identity* when the rightful owner shows
up — the owner gets a 409 and the squatter gets nothing. Closing it properly means invitation
semantics (unknown address → invite, not an account), the same conclusion #344 reached. **If WeWatch
ever drops the allowlist, build that before opening sign-up.**

### Testing

The two rejection cases in `UserServiceTest` fail against the pre-fix code (verified by deleting the
guard and re-running), so they are real regression guards rather than restatements. `AuthControllerTest`
covers the 409 through the full MockMvc stack and the no-throttle rule. `SignInPage.test.tsx` (the
page's first test) stubs the GIS global, captures the callback the page registers, and asserts the 409
renders the password-instead message — the Google callback used to bare-catch every failure into
"Sign-in failed. Please try again.", which would have left a user with a working password no way to
learn that it was the way in.

The Google link path **cannot be exercised by curl**: `GoogleTokenValidator` verifies the credential's
signature against Google's JWKS, so a forged one 401s long before the service runs. It is covered by
tests; email immutability was additionally verified by hand against the running app and local Postgres
(register a password account, `PATCH` its email to another allowlisted address → 200, address
unchanged in `users`, `display_name` still updatable, password sign-in still works).

## Serving layer — compression & caching (#347)

`frontend/nginx.conf.template` had no `gzip` and no `Cache-Control`. The `nginx:alpine` base image
ships `#gzip on;` *commented out* in its stock `nginx.conf` (verified on 1.31.2), so every cold load
pulled the whole bundle uncompressed; and the content-hashed `/assets/*` files — immutable by
construction — got only heuristic freshness plus a conditional revalidation on every visit. Both are
fixed in the config alone; no application code changed.

Measured against the built container: the JS bundle goes **316,678 → 94,998 bytes** over the wire
(3.3×), CSS 35.6 kB → 7.1 kB.

### The #337 trap is what dictates the design

The obvious `location /assets/ { add_header Cache-Control ...; }` is exactly the mistake the
[Security Headers](#security-headers-337) section warns about: nginx **replaces** the inherited
`add_header` set the moment a location declares one, so it would strip the CSP from every file the
app actually runs on. `expires` avoids that (it sets `Cache-Control` without `add_header`) but cannot
emit `immutable`.

So the value is computed by a **`map $uri $cache_control`** — the same idiom `$hsts_header` already
uses — and added **once, in `server`**, next to the other five headers. No location declares an
`add_header`, so `security-headers.test.ts`'s inheritance guard keeps passing unchanged.

| `$uri` | policy | why |
|---|---|---|
| `~^/api/` | `""` | nginx omits an `add_header` whose value is empty. Spring Security already sends `no-cache, no-store, max-age=0, must-revalidate`; anything we add either duplicates the header or *weakens* it to something a browser may write to disk. |
| `~^/assets/` | `public, max-age=31536000, immutable` | Vite content-hashes the filename, so the URL changes whenever the bytes do. `immutable` is the part that stops a reload revalidating them anyway. |
| default | `no-cache` | `index.html` names the hashed bundles, so it is the one file a deploy must invalidate. `no-cache` still *caches* — it revalidates before use — rather than pinning a stale document for a heuristic freshness window. |

⚠️ **The `Cache-Control` `add_header` deliberately omits `always`,** unlike the five security headers.
Without it `add_header` applies to 2xx/3xx only — which is exactly the set that should carry a cache
policy. A long-lived policy on an *error* response is how a bad deploy pins a 404 in someone's browser
for a year. The security headers want the opposite (they must ride on errors too), which is why the
two differ one line apart.

⚠️ **`gzip_proxied any` is load-bearing, not boilerplate.** The default (`off`) skips compression for
any request carrying a `Via` header — i.e. potentially everything arriving through Railway's edge. Left
at the default, this change would compress perfectly under local `docker compose` and *nothing* in
production.

⚠️ **`gzip_types` lists both JavaScript media types.** 1.31.2's `mime.types` maps `.js` to
`application/javascript`, but nginx has moved on this before; naming only the current one means a
future base-image bump silently stops compressing the largest file we serve, with nothing going red.
`text/html` is deliberately *absent*: it is compressed implicitly whenever `gzip` is on and is the one
type `gzip_types` cannot control. `application/manifest+json` has to be named because it is not in
nginx's `mime.types` at all — the #324 manifest location declares it by hand, and a test now couples
the two lists so a future custom type can't ship uncompressed.

### `/assets/` gets its own location, to keep it out of the SPA fallback

```nginx
location /assets/ { try_files $uri =404; }
```

A missing `/assets/*.js` used to answer **200 + `index.html`** (the #324 trap — the SPA fallback
swallows it and the browser parses a page of HTML as JavaScript). That was merely confusing before;
with a year-long `immutable` policy on the prefix it would be *cached*. The `=404` makes a bad deploy
fail loudly, and — per the `always` decision above — an error carries no cache policy at all, so
nothing about it persists.

Unversioned static files (`favicon.svg`, the PWA icons, `manifest.webmanifest`) fall to the `no-cache`
default and so revalidate. That is a deliberate trade: a handful of ~150-byte 304s per cold load, in
exchange for a changed icon or manifest propagating immediately. It is not worth a hashing scheme.

### Testing

`src/nginx-serving.test.ts` is the third file-on-disk test after `manifest.test.ts` (#324) and
`security-headers.test.ts` (#337), registered the same way (`exclude`d from `tsconfig.app.json`,
compiled by `tsconfig.node.json`, because it reads `node:fs`). It exists for the same reason they do:
**none of this is observable under `npm run dev`**, which serves the app from Vite with no nginx in the
loop — the config can only break in the built container, i.e. in production. It pins the gzip type
list, `gzip_proxied`, each branch of the cache map, the absence of `always` on `Cache-Control`, and the
`=404`. All 9 fail against the pre-#347 config.

The template is still never *executed* by any test, so the behavior was verified against the built
container: gzip fires on JS/CSS/SVG/manifest (and not on PNG); `/assets/*` carries
`public, max-age=31536000, immutable` **and** all five #337 headers; `/` and an SPA deep link carry
`no-cache`; a missing asset is a 404 with **no** `Cache-Control` but with the security headers intact;
the manifest keeps `application/manifest+json` *and* its CSP; HSTS still fires only with
`X-Forwarded-Proto: https`; and a proxied `/api/health` against the real backend carries exactly one
`Cache-Control` — Spring's own, `no-store` intact — with no duplicated `X-Frame-Options` or
`X-Content-Type-Options`.

`gzip_static` is compiled into the base image (`--with-http_gzip_static_module`), so precompressing the
bundle at build time is available as a follow-up: it would trade a little image-build time for a better
ratio (zopfli/`gzip -9`) and zero per-request CPU. Not done here — on-the-fly at `gzip_comp_level 5` is
already the bulk of the win.

## Forwarded protocol (#348)

Railway terminates TLS at its edge and forwards plain HTTP into the container, so **`$scheme` inside
nginx is `http` for every request that will ever reach it** — it describes the edge-to-container hop,
never the browser's. #337 already knew this (HSTS keys off `X-Forwarded-Proto`, not `$scheme`), but the
`/api/` proxy did not: it sent `proxy_set_header X-Forwarded-Proto $scheme`, overwriting the edge's real
value and telling the backend that every request in production arrived over plain HTTP. One header, two
readings, disagreeing about the same request.

Nothing consumes it server-side today — `server.forward-headers-strategy` is unset, so Spring ignores
`X-Forwarded-*` entirely, and the #336 throttle reads `X-Forwarded-For` — which is the only reason this
was latent rather than a live bug. The first feature to generate an absolute URL, set a `Secure` cookie,
or issue a proto-aware redirect would have inherited the lie.

### One map, two consumers

```nginx
map $http_x_forwarded_proto $forwarded_proto {
    "~^(?<edge_proto>https?)\b"  $edge_proto;
    default                      $scheme;
}
map $forwarded_proto $hsts_header { ... }   # was keyed on the raw header
```

The `/api/` proxy and the HSTS header now both read `$forwarded_proto`, so they cannot drift apart again.
Rewiring HSTS onto it is a fix in its own right: the old exact-match `https` key misses a *chained* value
(`https, http`) and would silently stop sending HSTS in production.

⚠️ **The regex is deliberate; the canonical pass-through idiom is wrong here.** The usual
`default $http_x_forwarded_proto; "" $scheme;` forwards whatever the caller sent, verbatim — and the
entire motivation for this change is that something downstream will eventually *build a URL* out of this
value. Only `http` or `https` can leave this map; anything else falls back to the protocol actually spoken
to the container. Case-sensitive on purpose: `~*` would capture `HTTPS` verbatim and then miss the
exact-match `https` key in `$hsts_header` below it.

⚠️ **Leftmost wins here; rightmost wins in `ClientIpResolver` (#336).** For a chained
`X-Forwarded-Proto: https, http` the **first** entry is the original client hop. For `X-Forwarded-For` the
**last** entry is the one a trusted proxy wrote, which is why that walk goes right to left. The two
conventions genuinely differ — the asymmetry looks like a bug and isn't.

⚠️ **`server.forward-headers-strategy` stays off.** Forwarding the correct value and *honoring* it are
separate decisions; only the first is in scope. Enabling it activates Tomcat's `RemoteIpValve`, which
rewrites `getRemoteAddr()` at the container level — the input to `ClientIpResolver`, which rejected that
valve precisely because no test in this suite starts a container to catch the interaction. Whoever first
needs the value on the backend should make that call with the throttle in front of them.

### Testing

`nginx-serving.test.ts` gains five assertions: the forwarded value, the `$scheme` fallback (the local
`docker compose` path — it publishes `3000:80` straight at nginx, so there is no edge header at all), that
the map can emit only `http`/`https`, that HSTS shares the source variable, and that the Dockerfile's
`envsubst` is given an explicit variable list. Four fail against the pre-#348 config.

That last one is new coverage rather than a regression guard: the template's top comment has always warned
that `$hsts_header`, `$forwarded_proto` and `$http_x_forwarded_proto` survive image build only because the
Dockerfile passes `envsubst '$BACKEND_URL'` — a bare `envsubst` blanks **every** `$`-token in the file, and
the config nginx runs would quietly be a different file from the one in this repo. Nothing pinned it.

The proxied value is a *request* header, so unlike #337/#347 it cannot be seen with `curl -I` against the
container. Verified by pointing `BACKEND_URL` at an echo server that prints the headers it receives:
`https` in → `https` forwarded (the bug: was `http`), `http` → `http`, **no header → `http`** (compose),
`https, http` → `https`, and `gopher://evil` → `http`, not the junk. HSTS still fires for an `https` edge
hop and still stays silent locally.
