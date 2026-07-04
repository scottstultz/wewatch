-- #247: suppression follows the user, not the watchlist. A title suggested in one
-- of a user's lists should stay suppressed across all of their lists (and, on a
-- shared list, across every member's suppression). Re-key impressions on user_id.

ALTER TABLE suggestion_impressions ADD COLUMN user_id BIGINT;

-- Backfill each existing impression to the owning user of its watchlist. Shared
-- lists collapse to their OWNER; the 7-day window means any imperfect mapping ages
-- out within a week, so a best-effort backfill is sufficient.
UPDATE suggestion_impressions si
SET user_id = wm.user_id
FROM watchlist_members wm
WHERE wm.watchlist_id = si.watchlist_id
  AND wm.role = 'OWNER';

-- Drop impressions we couldn't map to an owner (orphaned watchlists).
DELETE FROM suggestion_impressions WHERE user_id IS NULL;

-- Collapse duplicates the new (user_id, tmdb_id) key would reject — a user with
-- several lists may have recorded the same title more than once. Keep the most
-- recent impression, breaking exact ties by id.
DELETE FROM suggestion_impressions a
USING suggestion_impressions b
WHERE a.user_id = b.user_id
  AND a.tmdb_id = b.tmdb_id
  AND (a.last_shown_at < b.last_shown_at
       OR (a.last_shown_at = b.last_shown_at AND a.id < b.id));

ALTER TABLE suggestion_impressions
    DROP CONSTRAINT uq_suggestion_impressions_watchlist_tmdb;
DROP INDEX idx_suggestion_impressions_watchlist_shown;
ALTER TABLE suggestion_impressions
    DROP CONSTRAINT fk_suggestion_impressions_watchlist;
ALTER TABLE suggestion_impressions DROP COLUMN watchlist_id;

ALTER TABLE suggestion_impressions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE suggestion_impressions
    ADD CONSTRAINT fk_suggestion_impressions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE suggestion_impressions
    ADD CONSTRAINT uq_suggestion_impressions_user_tmdb
        UNIQUE (user_id, tmdb_id);

CREATE INDEX idx_suggestion_impressions_user_shown
    ON suggestion_impressions (user_id, last_shown_at);
