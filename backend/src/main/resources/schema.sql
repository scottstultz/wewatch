CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    provider VARCHAR(50),
    provider_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(50);
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS titles (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    overview VARCHAR(4000),
    release_date DATE,
    poster_url VARCHAR(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_titles_external_source_external_id UNIQUE (external_source, external_id)
);

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
