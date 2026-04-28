package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WatchlistEntryTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void tearDownValidator() {
		validatorFactory.close();
	}

	@Test
	void validWatchedEntryPassesValidation() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHED,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			Instant.parse("2026-04-28T12:01:00Z"),
			Instant.parse("2026-04-28T12:10:00Z")
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).isEmpty();
	}

	@Test
	void missingUserIdFailsValidation() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			null,
			20L,
			WatchStatus.WANT_TO_WATCH,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			null,
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("userId"));
	}

	@Test
	void watchedEntryRequiresWatchedDate() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHED,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			Instant.parse("2026-04-28T12:01:00Z"),
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validCompletedAt"));
	}

	@Test
	void unwatchedEntryCannotHaveCompletedAt() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHING,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			Instant.parse("2026-04-28T12:01:00Z"),
			Instant.parse("2026-04-28T12:10:00Z")
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validCompletedAt"));
	}

	@Test
	void missingTitleIdFailsValidation() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			null,
			WatchStatus.WANT_TO_WATCH,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			null,
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("titleId"));
	}

	@Test
	void watchingEntryRequiresStartedAt() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHING,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			null,
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validStartedAt"));
	}

	@Test
	void wantToWatchEntryCannotHaveStartedAt() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z"),
			Instant.parse("2026-04-28T12:01:00Z"),
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validStartedAt"));
	}
}
