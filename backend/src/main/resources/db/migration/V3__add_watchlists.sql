CREATE TABLE watchlists (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE watchlist_members (
    watchlist_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL,
    joined_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_watchlist_members PRIMARY KEY (watchlist_id, user_id),
    CONSTRAINT fk_watchlist_members_watchlist
        FOREIGN KEY (watchlist_id) REFERENCES watchlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

DROP TABLE watchlist_entries;

CREATE TABLE watchlist_entries (
    id                  BIGSERIAL PRIMARY KEY,
    watchlist_id        BIGINT      NOT NULL,
    added_by_user_id    BIGINT,
    title_id            BIGINT      NOT NULL,
    external_id         VARCHAR(255) NOT NULL,
    external_source     VARCHAR(100) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    added_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_watchlist_entries_watchlist
        FOREIGN KEY (watchlist_id) REFERENCES watchlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_entries_added_by
        FOREIGN KEY (added_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_watchlist_entries_watchlist_title
        UNIQUE (watchlist_id, title_id)
);

CREATE INDEX idx_watchlist_members_user_id      ON watchlist_members (user_id);
CREATE INDEX idx_watchlist_entries_watchlist_id ON watchlist_entries (watchlist_id);
