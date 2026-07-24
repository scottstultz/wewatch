# Offline suggestion-tuning harness (#288)

The taste profile in `SuggestionService` is built from many interacting
hand-tuned constants — profile weights, signal boosts, proportional jitter,
recency decay, positional demotion — each reasoned in isolation (#232, #248,
#264, #267, #269–#274) but never observable *together*. This harness replays a
set of fixture watchlists through the real `SuggestionService` against a
deterministic synthetic TMDB catalog and diffs the resulting shelves across
parameter changes, so the constants can be evaluated as a system instead of
eyeballed one at a time.

Everything is offline and fully reproducible: no live TMDB calls, and the same
fixtures + parameters + day seed produce byte-identical reports on any machine.

## Running it

```bash
cd backend
./mvnw test -Ptuning          # runs the tuning-tagged harness only
```

The harness is tagged `tuning` and **excluded from the default `./mvnw test`
run** (surefire `excludedGroups`), so it never slows CI. A separate always-on
guard, `SuggestionTuningHarnessTest`, does run in CI — it asserts the fixtures
load, shelves compute, a parameter change moves composition, and the sweep
rotates, so a broken fixture or wiring bug fails the normal build rather than
lurking until someone runs the harness by hand.

Each `@Test` in `SuggestionTuningHarness` writes a readable report to
`target/tuning/` (and stdout):

| Report | What it shows |
|---|---|
| `baseline-shelves.txt` | Every fixture's shelves under the shipped constants |
| `diff-keyword-weight-halved.txt` | Baseline vs `KEYWORD_MATCH_WEIGHT / 2` |
| `diff-half-life-doubled.txt` | Baseline vs `half-life × 2` |
| `sweep-week.txt` | Day-over-day rotation across a simulated week |

## The pieces

| File | Role |
|---|---|
| `SyntheticCatalog` | ~600-title deterministic TMDB universe (genres, keywords, people, votes, providers) and the feed methods (recommendations/similar/discover/trending/person/keyword/collection) that answer over it |
| `HarnessTmdbClient` | A `TmdbClient` subclass that serves the catalog (and any recorded overlay) instead of the network |
| `FixtureWatchlist` | JSON schema for a fixture (records) |
| `FixtureWorld` | Wires the offline collaborators for one fixture and computes its shelves for a given parameter set + day |
| `ImpressionStore` | In-memory `suggestion_impressions` so the real recency-penalty logic runs and accrues across a sweep |
| `TuningParameters` | A named `SuggestionTuningProperties` set; `baseline()` + `derive(name, mutation)` |
| `ShelfDiff` | Renders one parameter set and diffs two (entering/leaving, rank movement, overlap, summary stats) |
| `SuggestionTuningRunner` | Loads fixtures, runs a set, diffs two sets, sweeps days |

The tunable constants live in
`com.wewatch.api.config.SuggestionTuningProperties` (prefix
`suggestions.tuning.*`), extracted from `SuggestionService` so both the harness
and production config can inject them. The defaults are the historical constant
values, so an unset property changes nothing.

## Reading a report

`render` output — one parameter set:

```
● rich-single-genre  (6 shelves)
  ┌ PER_SEED  "Because you added Crimson Cartel"  ×12
  │   1. syn-385  Feral Frontier
  │   2. syn-545  Feral Meridian
  ...
  ┌ GENRE_PROFILE  "More like your shows"  [provider-filtered]  ×3
```

`diff` output — two parameter sets, matched by kind + label:

```
● heavily-rated   baseline → keyword-weight-halved
  ~ "PER_SEED | Because you added Restless Lantern"  overlap 85%
      out: syn-397          # left the shelf
      in:  syn-251          # entered the shelf
      moved: syn-387 ↑2     # rose 2 positions
  ── summary: shelves 6→6, mean shelf overlap 96%, mean |rank shift| 0.34, global title overlap 99%
```

`↓n` sinks `n` positions, `↑n` rises. Shelves present in only one set are
flagged `+ shelf appeared` / `- shelf dropped` (e.g. a seed shelf that fell
under the fresh-title floor and folded into the catch-all).

## Worked example: halving `KEYWORD_MATCH_WEIGHT`

`diff-keyword-weight-halved.txt` (`KEYWORD_MATCH_WEIGHT` 2.0 → 1.0). Keyword
overlap is a tiebreak on top of genre affinity, so halving it lets genre-only
peers reclaim rank from shared-theme candidates. The clearest effect is on
`heavily-rated`, where the demotion is enough to change shelf *membership*, not
just order:

```
● heavily-rated   baseline → keyword-weight-halved
  ~ "PER_SEED | Because you added Restless Lantern"  overlap 85%
      out: syn-397
      in:  syn-251
  ~ "GENRE_PROFILE | More like your shows"  overlap 92%
      out: syn-251
  ── summary: shelves 6→6, mean shelf overlap 96%, mean |rank shift| 0.34, global title overlap 99%
```

`syn-251` migrates out of the genre-profile shelf and into the seed shelf once
the keyword boost stops holding a rival ahead of it. On sparse profiles
(`sparse-profile`) the same change is inert — no keyword affinity to weaken —
which is itself the point: the harness shows *where* a constant matters.

A second example, `diff-half-life-doubled.txt` (recency half-life 90 → 180
days), moves `stale-history`'s per-seed and trending rankings as the years-old
crime titles reclaim genre weight from the fresh titles — but note how the
0.2 decay floor mutes it at ages already deep in the tail. That interaction is
exactly the kind of thing that's invisible when reasoning about one constant at
a time.

## Adding a fixture watchlist

Drop a JSON file in `watchlists/`. It's loaded automatically (files are sorted
by `name`). Aim for a regime not already covered — the current seven are
`sparse-profile`, `rich-single-genre`, `genre-mixed`, `heavily-rated`,
`stale-history`, `shared-household`, `hidden-gems`.

```jsonc
{
  "name": "my-regime",
  "description": "One line on what makes this interesting.",
  "watchlistId": 1007,               // unique across fixtures
  "members": [
    { "userId": 8, "region": "US", "providerIds": [8, 9] }  // region/providerIds null = provider-awareness off
  ],
  "entries": [
    {
      "titleId": 10701, "tmdbId": "w7-t1", "type": "TV", "name": "Example Show",
      "status": "WATCHING",           // WATCHING | WANT_TO_WATCH | WATCHED
      "ageDays": 7,                   // updated_at = base day (2026-07-06) minus this; drives #274 decay
      "rating": null,                 // "UP" | "DOWN" | null (already netted across members)
      "genreIds": [80, 53],           // real TMDB genre ids (see SyntheticCatalog.GENRE_POOL)
      "keywordIds": [100, 108],       // 100..139 map to named keywords in SyntheticCatalog
      "castIds": [910, 911],          // 900..959 map to synthetic people; reuse an id across ≥2 entries to trigger a PERSON shelf
      "directorIds": [930],
      "voteCount": 900,               // ≥300 = "rich" seed (#266); low = thin
      "providerIds": [8, 9],          // US streaming ids carrying this title (#270 badges)
      "releaseDate": "2024-09-12",
      "collectionId": null,           // set (movies only) to exercise the FRANCHISE shelf
      "collectionName": null
    }
  ],
  "dismissed": ["syn-140"]            // tmdb ids excluded from every shelf (#268)
}
```

Notes:
- **The fixture entry carries the `tmdb_title_cache` fields** (genres, keywords,
  cast, directors, votes, providers, collection). That's the *profile* side —
  what you own. The *candidate* side (what TMDB "returns") is always the shared
  synthetic catalog, so a fixture controls the profile while the catalog stays
  fixed. Reference genre/keyword/person ids from the catalog's pools so boosts
  actually fire against synthetic candidates.
- **`castIds`/`directorIds` in `900..959`** resolve to synthetic people whose
  filmographies exist in the catalog, so a `PERSON` shelf has stock.
- **`keywordIds` in `100..139`** carry display names, so a `KEYWORD` shelf can
  seed and label.
- Need ≥3 titles of a media type for its `GENRE_PROFILE` shelf; ≥2 shared
  people/keyword occurrences for those exploration shelves; a movie
  `collectionId` (on a `WATCHED`/`WATCHING` entry) for `FRANCHISE`.
- `BOTH_WATCH` (#322) needs ≥2 members who *all* set a region (the same one) and
  a `providerIds` list, with at least one id common to every member. One
  unconfigured member disables it — see `shared-household`.

## Recording real TMDB fixtures (optional overlay)

The default catalog is synthetic. To pin a specific feed to a *real* TMDB
response — e.g. to reproduce a production shelf exactly — drop a JSON file in
`recorded/`. Any recorded key takes precedence over the synthetic catalog for
that request; everything else stays synthetic.

```jsonc
// recorded/rec-w2-t1-page1.json
{
  "key": "recommendations:TV:w2-t1:1",   // "<feed>:<TitleType>:<tmdbId>:<page>", or "trending:<TitleType>:<page>"
  "titles": [
    { "externalId": "12345", "externalSource": "tmdb", "type": "TV", "name": "Real Title",
      "overview": null, "releaseDate": "2023-01-01", "posterUrl": null, "genreIds": [80], "providerIds": null }
  ]
}
```

Capture the raw response with the app's own TMDB token and reshape it to the
`TitleSearchResponse` array above, for example:

```bash
curl -s "https://api.themoviedb.org/3/tv/1399/recommendations?language=en-US&page=1" \
  -H "Authorization: Bearer $TMDB_API_KEY" | jq '.results'
```

Supported keys mirror the overridden feeds in `HarnessTmdbClient`:
`recommendations:`/`similar:<TitleType>:<tmdbId>:<page>` and
`trending:<TitleType>:<page>`. Discover-backed feeds (genre/keyword/person)
stay synthetic — their request space is too large to record usefully and the
catalog already answers them coherently.
