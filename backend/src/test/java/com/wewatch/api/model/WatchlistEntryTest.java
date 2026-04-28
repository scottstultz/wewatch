package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
			"Arrival",
			WatchStatus.WATCHED,
			5,
			"Rewatch candidate.",
			Instant.parse("2026-04-28T12:00:00Z"),
			LocalDate.parse("2026-04-27")
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).isEmpty();
	}

	@Test
	void blankTitleFailsValidation() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			" ",
			WatchStatus.WANT_TO_WATCH,
			null,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("titleName"));
	}

	@Test
	void watchedEntryRequiresWatchedDate() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			"Arrival",
			WatchStatus.WATCHED,
			null,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			null
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validWatchedDate"));
	}

	@Test
	void unwatchedEntryCannotHaveWatchedDate() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			"Arrival",
			WatchStatus.WATCHING,
			null,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			LocalDate.parse("2026-04-27")
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("validWatchedDate"));
	}

	@Test
	void ratingMustStayWithinMvpRange() {
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			"Arrival",
			WatchStatus.WATCHED,
			6,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			LocalDate.parse("2026-04-27")
		);

		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(entry);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("rating"));
	}
}
