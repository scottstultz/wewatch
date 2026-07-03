CREATE TABLE tmdb_season_cache (
    id            BIGSERIAL    PRIMARY KEY,
    tmdb_id       VARCHAR(255) NOT NULL REFERENCES tmdb_title_cache(tmdb_id),
    season_number INT          NOT NULL,
    name          VARCHAR(255),
    overview      TEXT,
    poster_path   VARCHAR(255),
    episode_count INT,
    air_date      DATE,
    fetched_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (tmdb_id, season_number)
);
