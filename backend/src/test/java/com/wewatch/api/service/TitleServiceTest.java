package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.wewatch.api.exception.DuplicateTitleException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TitleRepository;

class TitleServiceTest {

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
	void createSetsTimestampsWhenMissing() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title title = new Title(
			null,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			null,
			null,
			null,
			null
		);

		when(repository.findByExternalSourceAndExternalId("TMDB", "603")).thenReturn(Optional.empty());
		when(repository.create(any(Title.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Title created = service.create(title);

		assertThat(created.getCreatedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).create(title);
	}

	@Test
	void createRejectsInvalidTitle() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title title = new Title(
			null,
			"",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			null,
			null,
			Instant.now(),
			Instant.now()
		);

		assertThatThrownBy(() -> service.create(title)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void createRejectsDuplicateExternalTitle() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title existing = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			Instant.now(),
			Instant.now()
		);
		Title title = new Title(
			null,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			null,
			null,
			Instant.now(),
			Instant.now()
		);

		when(repository.findByExternalSourceAndExternalId("TMDB", "603")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.create(title)).isInstanceOf(DuplicateTitleException.class);
	}

	@Test
	void findByIdReturnsPersistedTitle() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title existing = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			Instant.now(),
			Instant.now()
		);

		when(repository.findById(1L)).thenReturn(Optional.of(existing));

		assertThat(service.findById(1L)).isEqualTo(existing);
	}

	@Test
	void findByExternalSourceAndExternalIdReturnsPersistedTitle() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title existing = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			Instant.now(),
			Instant.now()
		);

		when(repository.findByExternalSourceAndExternalId("TMDB", "603")).thenReturn(Optional.of(existing));

		assertThat(service.findByExternalSourceAndExternalId("TMDB", "603")).isEqualTo(existing);
	}
}
