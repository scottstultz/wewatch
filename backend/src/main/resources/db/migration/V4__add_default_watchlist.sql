ALTER TABLE watchlist_members
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- Each user's PERSONAL watchlist becomes their default
UPDATE watchlist_members wm
SET is_default = TRUE
FROM watchlists w
WHERE wm.watchlist_id = w.id
  AND w.type = 'PERSONAL';

-- Enforce exactly one default per user
CREATE UNIQUE INDEX uq_user_one_default
    ON watchlist_members (user_id)
    WHERE is_default = TRUE;
