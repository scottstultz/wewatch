-- #266: cached TMDB vote count as a cheap richness proxy for suggestion seed
-- selection. Nullable: rows refresh within the cache TTL; a NULL counts as thin.
ALTER TABLE tmdb_title_cache ADD COLUMN vote_count INTEGER;
