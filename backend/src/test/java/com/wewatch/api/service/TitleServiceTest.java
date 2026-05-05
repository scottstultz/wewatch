package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
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

	@Test
	void updateAppliesProvidedFieldsOnly() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existing = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			"Original overview",
			LocalDate.parse("1999-03-31"),
			"https://example.com/original.jpg",
			createdAt,
			createdAt
		);

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.update(any(Title.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Title updated = service.update(1L, "The Matrix Reloaded", null, null, null, TitleType.TV);

		assertThat(updated.getExternalId()).isEqualTo("603");
		assertThat(updated.getExternalSource()).isEqualTo("TMDB");
		assertThat(updated.getName()).isEqualTo("The Matrix Reloaded");
		assertThat(updated.getOverview()).isEqualTo("Original overview");
		assertThat(updated.getReleaseDate()).isEqualTo(LocalDate.parse("1999-03-31"));
		assertThat(updated.getPosterUrl()).isEqualTo("https://example.com/original.jpg");
		assertThat(updated.getType()).isEqualTo(TitleType.TV);
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getUpdatedAt()).isAfter(createdAt);
		verify(repository).update(existing);
	}

	@Test
	void updateRejectsMissingTitle() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);

		when(repository.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(42L, "The Matrix", null, null, null, null))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Title not found: 42");
	}

	@Test
	void updateRejectsInvalidMergedTitle() {
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

		assertThatThrownBy(() -> service.update(1L, "", null, null, null, null))
			.isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void findByFiltersReturnsMatchingTitles() {
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

		when(repository.findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix")).thenReturn(List.of(existing));

		assertThat(service.findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix")).containsExactly(existing);
		verify(repository).findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix");
	}

	@Test
	void findByFiltersNormalizesBlankValues() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);

		when(repository.findByFilters(null, null, null, null)).thenReturn(List.of());

		assertThat(service.findByFilters("", " ", null, "")).isEmpty();
		verify(repository).findByFilters(null, null, null, null);
	}
}
