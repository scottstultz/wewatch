-- #269: people-affinity signal. Top-billed cast and directors cached per title
-- as JSON arrays of {"id", "name"} — names are needed for shelf labels, so a
-- plain id list (the genre/keyword pattern) isn't enough. Credits ride the
-- existing detail fetches via append_to_response=credits; rows without people
-- fill in as the cache TTL refreshes them.
ALTER TABLE tmdb_title_cache ADD COLUMN top_cast TEXT;
ALTER TABLE tmdb_title_cache ADD COLUMN directors TEXT;
