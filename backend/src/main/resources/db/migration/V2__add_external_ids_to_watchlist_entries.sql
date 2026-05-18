ALTER TABLE watchlist_entries
    ADD COLUMN external_id VARCHAR(255),
    ADD COLUMN external_source VARCHAR(100);

UPDATE watchlist_entries we
SET external_id = t.external_id,
    external_source = t.external_source
FROM titles t
WHERE t.id = we.title_id;

ALTER TABLE watchlist_entries
    ALTER COLUMN external_id SET NOT NULL,
    ALTER COLUMN external_source SET NOT NULL;
