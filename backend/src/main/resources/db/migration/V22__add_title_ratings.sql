-- #273: per-user thumbs up/down title ratings — the taste profile's missing
-- negative half. Ratings are personal, so they key on (user, title) rather
-- than living on the shared watchlist_entries row: two members of a shared
-- list can rate the same title differently. One row per pair; re-rating
-- updates it in place.

CREATE TABLE title_ratings (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    title_id   BIGINT       NOT NULL,
    rating     VARCHAR(8)   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_title_ratings_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_title_ratings_title
        FOREIGN KEY (title_id) REFERENCES titles (id) ON DELETE CASCADE,
    CONSTRAINT uq_title_ratings_user_title
        UNIQUE (user_id, title_id),
    CONSTRAINT ck_title_ratings_rating
        CHECK (rating IN ('UP', 'DOWN'))
);
