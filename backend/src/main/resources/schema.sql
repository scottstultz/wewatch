CREATE TABLE IF NOT EXISTS watchlist_entries (
    id BIGSERIAL PRIMARY KEY,
    title_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    rating INTEGER,
    notes VARCHAR(2000),
    date_added TIMESTAMP WITH TIME ZONE NOT NULL,
    date_watched DATE
);
