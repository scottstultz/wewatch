-- #268: explicit "Not interested" dismissals. Unlike suggestion_impressions
-- (a rolling 7-day recency window), dismissals are user intent and never expire:
-- one row per (user, title), excluded from every shelf on every later compute.

CREATE TABLE suggestion_dismissals (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    tmdb_id      VARCHAR(255) NOT NULL,
    dismissed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_suggestion_dismissals_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_suggestion_dismissals_user_tmdb
        UNIQUE (user_id, tmdb_id)
);
