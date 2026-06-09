CREATE TABLE allowed_emails (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    added_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note       VARCHAR(255)
);
