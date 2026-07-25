package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
	void findOrCreateReturnsExistingTitleWithoutInvokingSupplier() {
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

		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "603", TitleType.MOVIE))
			.thenReturn(Optional.of(existing));

		Title resolved = service.findOrCreate("TMDB", "603", TitleType.MOVIE, () -> {
			throw new AssertionError("supplier must not be invoked when the title already exists");
		});

		assertThat(resolved).isEqualTo(existing);
		verify(repository, never()).save(any(Title.class));
	}

	@Test
	void findOrCreateSavesNewTitleWithTimestamps() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title candidate = new Title(
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

		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "603", TitleType.MOVIE))
			.thenReturn(Optional.empty());
		when(repository.save(any(Title.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Title created = service.findOrCreate("TMDB", "603", TitleType.MOVIE, () -> candidate);

		assertThat(created.getCreatedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).save(candidate);
	}

	@Test
	void findOrCreateRejectsInvalidCandidate() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);
		Title candidate = new Title(
			null,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"",
			null,
			null,
			null,
			null,
			null
		);

		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "603", TitleType.MOVIE))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findOrCreate("TMDB", "603", TitleType.MOVIE, () -> candidate))
			.isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void findOrCreateReturnsWinningRowAfterConcurrentInsert() {
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
		Title candidate = new Title(
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

		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "603", TitleType.MOVIE))
			.thenReturn(Optional.empty(), Optional.of(existing));
		when(repository.save(any(Title.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate key uq_titles_external_source_external_id_type"));

		Title resolved = service.findOrCreate("TMDB", "603", TitleType.MOVIE, () -> candidate);

		assertThat(resolved).isEqualTo(existing);
	}

	@Test
	void findOrCreateResolvesAColliddingMovieAndTvIdToDistinctTitles() {
		// #394: TMDB's movie and TV id sequences are independent namespaces — movie 550 is Fight
		// Club, tv 550 is Till Death Us Do Part. Matching on (source, id) alone let the second
		// medium added resolve to the first medium's row. Type is now part of the match, so a
		// movie lookup and a TV lookup for the same id must find (or create) two separate rows.
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);

		Title existingMovie = new Title(
			1L, "550", "TMDB", TitleType.MOVIE, "Fight Club", null,
			LocalDate.parse("1999-10-15"), null, Instant.now(), Instant.now());
		Title tvCandidate = new Title(
			null, "550", "TMDB", TitleType.TV, "Till Death Us Do Part", null,
			null, null, null, null);

		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "550", TitleType.MOVIE))
			.thenReturn(Optional.of(existingMovie));
		when(repository.findByExternalSourceAndExternalIdAndType("TMDB", "550", TitleType.TV))
			.thenReturn(Optional.empty());
		when(repository.save(any(Title.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Title resolvedMovie = service.findOrCreate("TMDB", "550", TitleType.MOVIE, () -> {
			throw new AssertionError("the existing movie must be found, not recreated");
		});
		Title resolvedTv = service.findOrCreate("TMDB", "550", TitleType.TV, () -> tvCandidate);

		assertThat(resolvedMovie).isEqualTo(existingMovie);
		assertThat(resolvedTv).isEqualTo(tvCandidate);
		assertThat(resolvedMovie.getExternalId()).isEqualTo(resolvedTv.getExternalId());
		assertThat(resolvedMovie.getType()).isNotEqualTo(resolvedTv.getType());
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

		when(repository.findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix", Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(existing)));

		assertThat(service.findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix", Pageable.unpaged()).getContent()).containsExactly(existing);
		verify(repository).findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix", Pageable.unpaged());
	}

	@Test
	void findByFiltersNormalizesBlankValues() {
		TitleRepository repository = Mockito.mock(TitleRepository.class);
		TitleService service = new TitleService(repository, validator);

		when(repository.findByFilters(null, null, null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of()));

		assertThat(service.findByFilters("", " ", null, "", Pageable.unpaged()).getContent()).isEmpty();
		verify(repository).findByFilters(null, null, null, null, Pageable.unpaged());
	}
}
