CREATE TABLE episode_progress (
    id                  BIGSERIAL PRIMARY KEY,
    watchlist_entry_id  BIGINT      NOT NULL,
    season_number       INT         NOT NULL,
    episode_number      INT         NOT NULL,
    watched             BOOLEAN     NOT NULL DEFAULT FALSE,
    watched_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_episode_progress_entry
        FOREIGN KEY (watchlist_entry_id) REFERENCES watchlist_entries (id) ON DELETE CASCADE,
    CONSTRAINT uq_episode_progress_entry_season_episode
        UNIQUE (watchlist_entry_id, season_number, episode_number)
);

CREATE INDEX idx_episode_progress_entry_id ON episode_progress (watchlist_entry_id);
