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

class WatchlistTest {

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
	void validPersonalWatchlistPassesValidation() {
		Watchlist watchlist = new Watchlist(1L, "My Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).isEmpty();
	}

	@Test
	void validSharedWatchlistPassesValidation() {
		Watchlist watchlist = new Watchlist(1L, "Our Watchlist", WatchlistType.SHARED, Instant.now(), Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).isEmpty();
	}

	@Test
	void missingNameFailsValidation() {
		Watchlist watchlist = new Watchlist(1L, null, WatchlistType.PERSONAL, Instant.now(), Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
	}

	@Test
	void blankNameFailsValidation() {
		Watchlist watchlist = new Watchlist(1L, "  ", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
	}

	@Test
	void missingTypeFailsValidation() {
		Watchlist watchlist = new Watchlist(1L, "My Watchlist", null, Instant.now(), Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("type"));
	}

	@Test
	void missingCreatedAtFailsValidation() {
		Watchlist watchlist = new Watchlist(1L, "My Watchlist", WatchlistType.PERSONAL, null, Instant.now());

		Set<ConstraintViolation<Watchlist>> violations = validator.validate(watchlist);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("createdAt"));
	}
}
