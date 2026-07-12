-- #323: the stats page sums watch time across finished movies and watched episodes.
-- Episode runtimes were already cached (tmdb_episode_cache.runtime_minutes), but a
-- movie's runtime was fetched on every detail call and thrown away — upsertMovieCache
-- never persisted it — so movie watch time was unanswerable without N TMDB calls at
-- page load.
--
-- NULL means "not fetched since #323". Unlike TV rows, movie cache rows have no TTL
-- refresh path (only getSeasons/getSeasonDetail re-fetch), so nothing would ever
-- populate this on its own: TmdbCacheBackfill gained a startup pass that re-prewarms
-- movies where this is NULL. TV rows leave it NULL for good — a show's runtime lives
-- per-episode.

ALTER TABLE tmdb_title_cache ADD COLUMN runtime_minutes INT;
