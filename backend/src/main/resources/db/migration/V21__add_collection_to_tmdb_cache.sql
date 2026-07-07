-- #272: franchise-continuation shelf needs the TMDB collection a movie belongs
-- to (belongs_to_collection on the existing movie detail call — no extra
-- request). TV has no collections, so these stay null on TV rows.
ALTER TABLE tmdb_title_cache ADD COLUMN collection_id INTEGER;
ALTER TABLE tmdb_title_cache ADD COLUMN collection_name VARCHAR(255);
