CREATE TABLE tmdb_title_cache (
    tmdb_id          VARCHAR(255) PRIMARY KEY,
    type             VARCHAR(32)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    overview         TEXT,
    poster_path      VARCHAR(255),
    status           VARCHAR(100),
    first_air_date   DATE,
    number_of_seasons INT,
    fetched_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE tmdb_episode_cache (
    id             BIGSERIAL    PRIMARY KEY,
    tmdb_id        VARCHAR(255) NOT NULL REFERENCES tmdb_title_cache(tmdb_id),
    season_number  INT          NOT NULL,
    episode_number INT          NOT NULL,
    name           VARCHAR(255),
    overview       TEXT,
    air_date       DATE,
    runtime_minutes INT,
    still_path     VARCHAR(255),
    fetched_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (tmdb_id, season_number, episode_number)
);
