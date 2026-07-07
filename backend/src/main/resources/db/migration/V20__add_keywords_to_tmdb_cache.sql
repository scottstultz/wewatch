-- #271: keyword-seeded shelves need display names, so keywords are cached as a
-- JSON array of {"id", "name"} alongside the keyword_ids CSV that feeds scoring
-- (same split as top_cast/directors vs genre_ids, #269). Rows fill in as the
-- cache TTL refreshes them; until then the keyword shelf yields its slot.
ALTER TABLE tmdb_title_cache ADD COLUMN keywords TEXT;
