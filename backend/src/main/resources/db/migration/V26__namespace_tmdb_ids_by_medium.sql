-- #394: TMDB's movie and TV id sequences are independent namespaces — the same integer names a
-- different title in each medium (movie 550 Fight Club / tv 550 Till Death Us Do Part; movie 1396
-- Mirror / tv 1396 Breaking Bad). Three keys treated a bare id as a medium-agnostic identity, so
-- whichever medium was written first owned the id for the other:
--
--   titles            UNIQUE (external_source, external_id)  -- V1, external_source is always 'TMDB'
--   tmdb_title_cache  tmdb_id VARCHAR(255) PRIMARY KEY       -- V9, no medium namespace
--   tmdb_{season,episode}_cache  tmdb_id REFERENCES the above -- V12 / V9
--
-- Both are fixed here: titles gains type in its uniqueness, and the cache key becomes
-- 'movie:550' / 'tv:550'. Namespacing the cache is what makes its seven readers correct by
-- construction rather than each needing its own medium guard.

-- ── titles ──────────────────────────────────────────────────────────────────
-- Widening a unique key can never conflict: any pair of rows distinct under
-- (external_source, external_id) stays distinct with type appended. So unlike V25 (#345) there is
-- no decision to make here and no reason to abort — the constraint simply admits more rows than
-- it did before. This is the half the user actually sees: without it, adding tv 550 to a
-- watchlist silently resolves to the Fight Club row and the show is untrackable per-episode.
ALTER TABLE titles DROP CONSTRAINT uq_titles_external_source_external_id;
ALTER TABLE titles ADD CONSTRAINT uq_titles_external_source_external_id_type
    UNIQUE (external_source, external_id, type);

-- ── tmdb_title_cache and its children ───────────────────────────────────────
-- The children are TV-only, so they take the 'tv:' form unconditionally. Their FK is dropped and
-- re-added rather than declared ON UPDATE CASCADE: the cascade would only help the parent update
-- below, and re-adding the constraint at the end is what actually re-verifies that every child
-- still points at a real parent once both sides have moved.
ALTER TABLE tmdb_episode_cache DROP CONSTRAINT tmdb_episode_cache_tmdb_id_fkey;
ALTER TABLE tmdb_season_cache DROP CONSTRAINT tmdb_season_cache_tmdb_id_fkey;

-- Prefixing an existing PRIMARY KEY is injective, and the two prefixes are disjoint, so this
-- UPDATE cannot collide either. lower(type) matches TmdbCacheKey.prefix() on the Java side.
UPDATE tmdb_title_cache SET tmdb_id = lower(type) || ':' || tmdb_id;
UPDATE tmdb_episode_cache SET tmdb_id = 'tv:' || tmdb_id;
UPDATE tmdb_season_cache  SET tmdb_id = 'tv:' || tmdb_id;

-- The one genuine conflict this migration can meet: a cache row that holds episodes or seasons but
-- is *currently typed MOVIE*. That is the oscillation described in #394 — upsertTvCache and
-- upsertMovieCache both loaded by bare id and setType() on the same row, so last prewarm won, and
-- a collided id could flip back and forth (TV rows TTL-refresh on read while the #323 movie
-- runtime backfill re-prewarms movies at every boot). Its children are now 'tv:X' with no 'tv:X'
-- parent, and the FK re-add below would fail.
--
-- Resolved rather than aborted, unlike V25's users check. The distinction is ownership of data:
-- V25 refused to merge two accounts because each holds a human's watchlists and progress, but
-- deduped allowed_emails because an allowlist row owns nothing. Season and episode cache rows own
-- nothing either — they are derived TMDB data, rebuildable in full, and episode_progress keys on
-- (watchlist_entry_id, season_number, episode_number) with no FK to the cache, so a user's ticks
-- survive untouched. TmdbCacheBackfill is medium-aware from #394 onward, so the affected show is
-- prewarmed on the next boot and its seasons and episodes come straight back.
DO $$
DECLARE
    orphaned_episodes BIGINT;
    orphaned_seasons  BIGINT;
BEGIN
    DELETE FROM tmdb_episode_cache e
     WHERE NOT EXISTS (SELECT 1 FROM tmdb_title_cache c WHERE c.tmdb_id = e.tmdb_id);
    GET DIAGNOSTICS orphaned_episodes = ROW_COUNT;

    DELETE FROM tmdb_season_cache s
     WHERE NOT EXISTS (SELECT 1 FROM tmdb_title_cache c WHERE c.tmdb_id = s.tmdb_id);
    GET DIAGNOSTICS orphaned_seasons = ROW_COUNT;

    IF orphaned_episodes > 0 OR orphaned_seasons > 0 THEN
        RAISE NOTICE
            'Dropped % orphaned episode and % orphaned season cache row(s) whose parent title was cached as a MOVIE (#394 collision). TmdbCacheBackfill will re-prewarm them.',
            orphaned_episodes, orphaned_seasons;
    END IF;
END $$;

ALTER TABLE tmdb_episode_cache ADD CONSTRAINT tmdb_episode_cache_tmdb_id_fkey
    FOREIGN KEY (tmdb_id) REFERENCES tmdb_title_cache(tmdb_id);
ALTER TABLE tmdb_season_cache ADD CONSTRAINT tmdb_season_cache_tmdb_id_fkey
    FOREIGN KEY (tmdb_id) REFERENCES tmdb_title_cache(tmdb_id);
