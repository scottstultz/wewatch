-- #264: replace binary suppression with a soft recency penalty. The penalty reads
-- the most recent *previous-day* showing per title, and that value must stay
-- readable all day even after a title is re-served and re-recorded — a single
-- overwritten last_shown_at can't hold both facts, so impressions become one row
-- per (user, title, day).

ALTER TABLE suggestion_impressions ADD COLUMN shown_on DATE;

-- Best-effort backfill (V14 precedent): the penalty window means any timezone
-- imprecision in the date truncation ages out within a week.
UPDATE suggestion_impressions SET shown_on = (last_shown_at AT TIME ZONE 'UTC')::date;

ALTER TABLE suggestion_impressions ALTER COLUMN shown_on SET NOT NULL;

ALTER TABLE suggestion_impressions
    DROP CONSTRAINT uq_suggestion_impressions_user_tmdb;
DROP INDEX idx_suggestion_impressions_user_shown;
ALTER TABLE suggestion_impressions DROP COLUMN last_shown_at;

ALTER TABLE suggestion_impressions
    ADD CONSTRAINT uq_suggestion_impressions_user_tmdb_day
        UNIQUE (user_id, tmdb_id, shown_on);

CREATE INDEX idx_suggestion_impressions_user_shown
    ON suggestion_impressions (user_id, shown_on);
