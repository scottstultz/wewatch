CREATE TABLE suggestion_impressions (
    id            BIGSERIAL    PRIMARY KEY,
    watchlist_id  BIGINT       NOT NULL,
    tmdb_id       VARCHAR(255) NOT NULL,
    last_shown_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_suggestion_impressions_watchlist
        FOREIGN KEY (watchlist_id) REFERENCES watchlists (id) ON DELETE CASCADE,
    CONSTRAINT uq_suggestion_impressions_watchlist_tmdb
        UNIQUE (watchlist_id, tmdb_id)
);

CREATE INDEX idx_suggestion_impressions_watchlist_shown
    ON suggestion_impressions (watchlist_id, last_shown_at);
