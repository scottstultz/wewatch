package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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

import com.wewatch.api.exception.DuplicateWatchlistEntryException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.model.WatchlistType;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistRepository;

class WatchlistEntryServiceTest {

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

	private static Watchlist watchlist(Long id) {
		return new Watchlist(id, "My Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());
	}

	@Test
	void createSetsDateAddedWhenMissing() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByWatchlistIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getAddedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		assertThat(created.getExternalId()).isEqualTo("603");
		assertThat(created.getExternalSource()).isEqualTo("TMDB");
		verify(repository).save(entry);
	}

	@Test
	void createRejectsInvalidEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			Mockito.mock(WatchlistRepository.class),
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry entry = new WatchlistEntry(null, null, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		assertThatThrownBy(() -> service.create(entry)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void createDefaultsMissingStatusToWantToWatch() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, null, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByWatchlistIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStatus()).isEqualTo(WatchStatus.WANT_TO_WATCH);
		assertThat(created.getStartedAt()).isNull();
		assertThat(created.getCompletedAt()).isNull();
	}

	@Test
	void createSetsStartedAtForWatchingEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WATCHING, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByWatchlistIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStartedAt()).isNotNull();
		assertThat(created.getCompletedAt()).isNull();
	}

	@Test
	void createSetsStartedAtAndCompletedAtForWatchedEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WATCHED, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByWatchlistIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStartedAt()).isNotNull();
		assertThat(created.getCompletedAt()).isNotNull();
	}

	@Test
	void createRejectsMissingWatchlist() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(entry))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Watchlist not found: 10");
	}

	@Test
	void createRejectsMissingTitle() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenThrow(new NoSuchElementException("Title not found: 20"));

		assertThatThrownBy(() -> service.create(entry))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Title not found: 20");
	}

	@Test
	void createRejectsDuplicateWatchlistTitleEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, watchlistRepository, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);
		WatchlistEntry existingEntry = new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByWatchlistIdAndTitleId(10L, 20L)).thenReturn(Optional.of(existingEntry));

		assertThatThrownBy(() -> service.create(entry)).isInstanceOf(DuplicateWatchlistEntryException.class);
	}

	@Test
	void updatePreservesOriginalDateAddedWhenOmitted() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			Mockito.mock(WatchlistRepository.class),
			Mockito.mock(TitleService.class)
		);
		Instant originalDateAdded = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry existingEntry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			originalDateAdded,
			Instant.parse("2026-04-28T12:10:00Z"),
			null,
			null
		);
		WatchlistEntry updatedEntry = new WatchlistEntry(
			null,
			999L,
			30L,
			WatchStatus.WATCHED,
			null,
			null,
			Instant.parse("2026-04-28T12:15:00Z"),
			Instant.parse("2026-04-28T12:30:00Z")
		);

		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.of(existingEntry));
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(10L, 1L, updatedEntry);

		assertThat(result.getId()).isEqualTo(1L);
		assertThat(result.getWatchlistId()).isEqualTo(10L);
		assertThat(result.getTitleId()).isEqualTo(20L);
		assertThat(result.getAddedAt()).isEqualTo(originalDateAdded);
		assertThat(result.getUpdatedAt()).isNotNull();
		verify(repository).save(updatedEntry);
	}

	@Test
	void updateWatchedToWatchingClearsCompletedAt() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository, validator, Mockito.mock(WatchlistRepository.class), Mockito.mock(TitleService.class)
		);
		Instant started = Instant.parse("2026-04-28T12:00:00Z");
		Instant completed = Instant.parse("2026-04-28T14:00:00Z");
		WatchlistEntry existingEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WATCHED,
			Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-28T14:00:00Z"),
			started, completed
		);
		WatchlistEntry update = new WatchlistEntry(null, null, null, WatchStatus.WATCHING, null, null, null, null);

		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.of(existingEntry));
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(10L, 1L, update);

		assertThat(result.getStatus()).isEqualTo(WatchStatus.WATCHING);
		assertThat(result.getStartedAt()).isEqualTo(started);
		assertThat(result.getCompletedAt()).isNull();
	}

	@Test
	void updateWatchingToWantToWatchClearsStartedAt() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository, validator, Mockito.mock(WatchlistRepository.class), Mockito.mock(TitleService.class)
		);
		Instant started = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry existingEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WATCHING,
			Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-28T12:00:00Z"),
			started, null
		);
		WatchlistEntry update = new WatchlistEntry(null, null, null, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.of(existingEntry));
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(10L, 1L, update);

		assertThat(result.getStatus()).isEqualTo(WatchStatus.WANT_TO_WATCH);
		assertThat(result.getStartedAt()).isNull();
		assertThat(result.getCompletedAt()).isNull();
	}

	@Test
	void updateWatchedToWantToWatchClearsBothTimestamps() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository, validator, Mockito.mock(WatchlistRepository.class), Mockito.mock(TitleService.class)
		);
		Instant started = Instant.parse("2026-04-28T12:00:00Z");
		Instant completed = Instant.parse("2026-04-28T14:00:00Z");
		WatchlistEntry existingEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WATCHED,
			Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-28T14:00:00Z"),
			started, completed
		);
		WatchlistEntry update = new WatchlistEntry(null, null, null, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.of(existingEntry));
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(10L, 1L, update);

		assertThat(result.getStatus()).isEqualTo(WatchStatus.WANT_TO_WATCH);
		assertThat(result.getStartedAt()).isNull();
		assertThat(result.getCompletedAt()).isNull();
	}

	@Test
	void findAllDelegatesToRepositoryForWatchlist() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			watchlistRepository,
			Mockito.mock(TitleService.class)
		);
		List<WatchlistEntry> entries = List.of(
			new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null)
		);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(repository.findByWatchlistId(10L, null, Pageable.unpaged())).thenReturn(new PageImpl<>(entries));

		assertThat(service.findByFilters(10L, null, Pageable.unpaged()).getContent()).containsExactlyElementsOf(entries);
	}

	@Test
	void findByFiltersPassesStatusToRepository() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			watchlistRepository,
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry watching = new WatchlistEntry(2L, 10L, 30L, WatchStatus.WATCHING, Instant.now(), Instant.now(), Instant.now(), null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(repository.findByWatchlistId(10L, WatchStatus.WATCHING, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(watching)));

		assertThat(service.findByFilters(10L, WatchStatus.WATCHING, Pageable.unpaged()).getContent()).containsExactly(watching);
		verify(repository).findByWatchlistId(10L, WatchStatus.WATCHING, Pageable.unpaged());
	}

	@Test
	void findByFiltersRejectsMissingWatchlist() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			watchlistRepository,
			Mockito.mock(TitleService.class)
		);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findByFilters(10L, null, Pageable.unpaged()))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Watchlist not found: 10");
	}

	@Test
	void findByIdReturnsEntryForWatchlist() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			watchlistRepository,
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry entry = new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.of(entry));

		assertThat(service.findById(10L, 1L)).isEqualTo(entry);
	}

	@Test
	void findByIdRejectsMissingEntryForWatchlist() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			watchlistRepository,
			Mockito.mock(TitleService.class)
		);

		when(watchlistRepository.findById(10L)).thenReturn(Optional.of(watchlist(10L)));
		when(repository.findByIdAndWatchlistId(1L, 10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findById(10L, 1L))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Watchlist entry not found: 1");
	}
}
