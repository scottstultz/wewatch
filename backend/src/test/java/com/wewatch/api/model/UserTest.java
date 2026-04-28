package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserTest {

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
	void validUserPassesValidation() {
		User user = new User(
			1L,
			"user@example.com",
			"Scott",
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z")
		);

		Set<ConstraintViolation<User>> violations = validator.validate(user);

		assertThat(violations).isEmpty();
	}

	@Test
	void missingEmailFailsValidation() {
		User user = new User(
			1L,
			"",
			"Scott",
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z")
		);

		Set<ConstraintViolation<User>> violations = validator.validate(user);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("email"));
	}

	@Test
	void emailIsMarkedUniqueAtEntityLevel() throws NoSuchFieldException {
		Column column = User.class.getDeclaredField("email").getAnnotation(Column.class);

		assertThat(column).isNotNull();
		assertThat(column.unique()).isTrue();
	}

	@Test
	void emailIsIncludedInTableUniqueConstraint() {
		Table table = User.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		assertThat(table.uniqueConstraints())
			.extracting(UniqueConstraint::name)
			.contains("uq_users_email");
	}
}
