package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TitleTest {

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
	void validMovieTitlePassesValidation() {
		Title title = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			"A hacker discovers reality is simulated.",
			LocalDate.parse("1999-03-31"),
			"https://image.tmdb.org/t/p/w500/matrix.jpg",
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z")
		);

		Set<ConstraintViolation<Title>> violations = validator.validate(title);

		assertThat(violations).isEmpty();
	}

	@Test
	void missingExternalIdFailsValidation() {
		Title title = new Title(
			1L,
			"",
			"TMDB",
			TitleType.TV,
			"Severance",
			null,
			null,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z")
		);

		Set<ConstraintViolation<Title>> violations = validator.validate(title);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("externalId"));
	}

	@Test
	void missingTypeFailsValidation() {
		Title title = new Title(
			1L,
			"603",
			"TMDB",
			null,
			"The Matrix",
			null,
			null,
			null,
			Instant.parse("2026-04-28T12:00:00Z"),
			Instant.parse("2026-04-28T12:05:00Z")
		);

		Set<ConstraintViolation<Title>> violations = validator.validate(title);

		assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("type"));
	}

	@Test
	void externalSourceAndExternalIdAndTypeHaveUniqueConstraint() {
		// #394: TMDB movie and TV ids are independent namespaces, so the constraint must include
		// type — (source, id) alone let tv 550 resolve to the movie 550 row. This only pins the
		// entity annotation (inert under ddl-auto=none); the enforcement is V26, asserted from the
		// classpath the same way UserEmailUniquenessTest (#345) pins V25.
		Table table = Title.class.getAnnotation(Table.class);

		assertThat(table).isNotNull();
		UniqueConstraint constraint = java.util.Arrays.stream(table.uniqueConstraints())
			.filter(c -> c.name().equals("uq_titles_external_source_external_id_type"))
			.findFirst()
			.orElse(null);
		assertThat(constraint).isNotNull();
		assertThat(constraint.columnNames()).containsExactlyInAnyOrder(
			"external_source", "external_id", "type");
	}
}
