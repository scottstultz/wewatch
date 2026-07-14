-- #345: email is the identity key, but uq_users_email (V1) and allowed_emails.email (V8) are
-- both case-sensitive UNIQUE, while the allowlist check reads with LOWER(). Canonicalize the
-- stored form and make uniqueness case-insensitive so the two layers cannot drift apart again.

-- Refuse to guess. Two accounts differing only by case hold two humans' watchlists, ratings and
-- episode progress; merging them is a data decision, not something a migration may do silently.
-- Fail closed: Flyway aborts, the app will not start, and a human resolves it by hand.
DO $$
DECLARE
    collisions TEXT;
BEGIN
    SELECT string_agg(k, ', ')
      INTO collisions
      FROM (SELECT lower(email) AS k FROM users GROUP BY 1 HAVING count(*) > 1) c;

    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot normalize users.email: accounts differ only by case (%). Merge them by hand, then re-run.',
            collisions;
    END IF;
END $$;

UPDATE users SET email = lower(email) WHERE email <> lower(email);

-- registerWithPassword stores the address a second time in provider_id; leaving it mixed-case
-- would strand findByProviderAndProviderId against the now-normalized email column.
UPDATE users SET provider_id = lower(provider_id)
 WHERE provider = 'email' AND provider_id <> lower(provider_id);

ALTER TABLE users DROP CONSTRAINT uq_users_email;
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- allowed_emails: reads were already case-insensitive (existsByEmailIgnoreCase), but the UNIQUE
-- was not, so two rows for one human could coexist. Unlike users, deduping here is lossless — an
-- allowlist row owns no data — so the later duplicate is simply dropped.
DELETE FROM allowed_emails a
      USING allowed_emails b
      WHERE lower(a.email) = lower(b.email) AND a.id > b.id;

UPDATE allowed_emails SET email = lower(email) WHERE email <> lower(email);

ALTER TABLE allowed_emails DROP CONSTRAINT allowed_emails_email_key;
CREATE UNIQUE INDEX uq_allowed_emails_email_lower ON allowed_emails (lower(email));
