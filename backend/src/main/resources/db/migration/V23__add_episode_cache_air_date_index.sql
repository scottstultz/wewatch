-- #321: "Returning this week" scans tmdb_episode_cache for episodes airing in a
-- date window, joined to the watchlist's WATCHING TV entries. The table's only
-- index today is the unique (tmdb_id, season_number, episode_number), which does
-- nothing for a date-range predicate — the window is highly selective (a week out
-- of a show's entire run), so an air_date index turns the range scan into a seek.

CREATE INDEX idx_tmdb_episode_cache_air_date ON tmdb_episode_cache (air_date);
