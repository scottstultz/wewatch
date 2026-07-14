package com.wewatch.api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins how email uniqueness is actually enforced (#345).
 *
 * <p>After #345 the invariant "one account per address, regardless of casing" is enforced by the
 * database, not by the entity — {@code UNIQUE (lower(email))}. Nothing else in this suite can see
 * that: no test executes SQL (there is no {@code @DataJpaTest} / Testcontainers anywhere in
 * {@code backend/src/test}), so V25 could be deleted, or silently ship the old case-sensitive
 * constraint, with a green build. This reads the migration that actually ships and asserts the
 * shape of it — the same trick {@code ClientIpResolverTest} uses on the shipped
 * {@code trusted-proxies} default.
 *
 * <p>It is a structural check, not a substitute for running the migration: the SQL itself is
 * verified by hand against local Postgres.
 */
class UserEmailUniquenessTest {

	private static String migration;

	@BeforeAll
	static void loadMigration() throws Exception {
		try (InputStream in = UserEmailUniquenessTest.class
				.getResourceAsStream("/db/migration/V25__normalize_email_case.sql")) {
			assertThat(in).as("V25 migration must ship on the classpath").isNotNull();
			migration = new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
		}
	}

	@Test
	void replacesTheCaseSensitiveUsersConstraintWithAFunctionalUniqueIndex() {
		assertThat(migration).contains("alter table users drop constraint uq_users_email");
		assertThat(migration).contains("create unique index uq_users_email_lower on users (lower(email))");
	}

	@Test
	void canonicalizesStoredAddresses() {
		assertThat(migration).contains("update users set email = lower(email)");
		// providerId is a second copy of the address for password users (UserService.registerWithPassword).
		assertThat(migration).contains("update users set provider_id = lower(provider_id)");
	}

	@Test
	void refusesToRunWhenTwoAccountsDifferOnlyByCase() {
		// Merging two accounts means merging two humans' watchlists, ratings and progress — the
		// migration must abort and let a person decide, never silently pick a winner.
		assertThat(migration).contains("raise exception");
		assertThat(migration).contains("group by 1 having count(*) > 1");
	}

	@Test
	void appliesTheSameCaseInsensitiveUniquenessToTheAllowlist() {
		assertThat(migration).contains("alter table allowed_emails drop constraint allowed_emails_email_key");
		assertThat(migration)
			.contains("create unique index uq_allowed_emails_email_lower on allowed_emails (lower(email))");
	}
}
