CREATE TABLE IF NOT EXISTS watchlist_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_watchlist_entries_user_title UNIQUE (user_id, title_id)
);
