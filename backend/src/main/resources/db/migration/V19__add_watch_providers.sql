-- #270: watch-provider integration. Users record the streaming services they
-- subscribe to (TMDB provider ids, CSV) and a watch region; titles cache their
-- flatrate (subscription) provider ids per region as a JSON object of
-- region -> [provider ids]. Provider data is TMDB's JustWatch-licensed
-- watch/providers block, riding the existing detail calls via
-- append_to_response — rows fill in as the cache TTL refreshes them.
ALTER TABLE users ADD COLUMN watch_region VARCHAR(2);
ALTER TABLE users ADD COLUMN watch_provider_ids VARCHAR(500);
ALTER TABLE tmdb_title_cache ADD COLUMN watch_providers TEXT;
