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
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.WatchlistEntryRepository;

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

	@Test
	void createSetsDateAddedWhenMissing() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByUserIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getAddedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).save(entry);
	}

	@Test
	void createRejectsInvalidEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			Mockito.mock(UserService.class),
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry entry = new WatchlistEntry(null, null, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		assertThatThrownBy(() -> service.create(entry)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void createDefaultsMissingStatusToWantToWatch() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, null, null, null, null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByUserIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStatus()).isEqualTo(WatchStatus.WANT_TO_WATCH);
		assertThat(created.getStartedAt()).isNull();
		assertThat(created.getCompletedAt()).isNull();
	}

	@Test
	void createSetsStartedAtForWatchingEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WATCHING, null, null, null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByUserIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStartedAt()).isNotNull();
		assertThat(created.getCompletedAt()).isNull();
	}

	@Test
	void createSetsStartedAtAndCompletedAtForWatchedEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WATCHED, null, null, null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByUserIdAndTitleId(10L, 20L)).thenReturn(Optional.empty());
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getStartedAt()).isNotNull();
		assertThat(created.getCompletedAt()).isNotNull();
	}

	@Test
	void createRejectsMissingUser() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(userService.findById(10L)).thenThrow(new NoSuchElementException("User not found: 10"));

		assertThatThrownBy(() -> service.create(entry))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("User not found: 10");
	}

	@Test
	void createRejectsMissingTitle() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenThrow(new NoSuchElementException("Title not found: 20"));

		assertThatThrownBy(() -> service.create(entry))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Title not found: 20");
	}

	@Test
	void createRejectsDuplicateUserTitleEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		TitleService titleService = Mockito.mock(TitleService.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator, userService, titleService);
		WatchlistEntry entry = new WatchlistEntry(null, 10L, 20L, WatchStatus.WANT_TO_WATCH, null, null, null, null);
		WatchlistEntry existingEntry = new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(titleService.findById(20L)).thenReturn(new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, Instant.now(), Instant.now()));
		when(repository.findByUserIdAndTitleId(10L, 20L)).thenReturn(Optional.of(existingEntry));

		assertThatThrownBy(() -> service.create(entry)).isInstanceOf(DuplicateWatchlistEntryException.class);
	}

	@Test
	void updatePreservesOriginalDateAddedWhenOmitted() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			Mockito.mock(UserService.class),
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

		when(repository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.of(existingEntry));
		when(repository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(10L, 1L, updatedEntry);

		assertThat(result.getId()).isEqualTo(1L);
		assertThat(result.getUserId()).isEqualTo(10L);
		assertThat(result.getTitleId()).isEqualTo(30L);
		assertThat(result.getAddedAt()).isEqualTo(originalDateAdded);
		assertThat(result.getUpdatedAt()).isNotNull();
		verify(repository).save(updatedEntry);
	}

	@Test
	void findAllDelegatesToRepositoryForUser() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			userService,
			Mockito.mock(TitleService.class)
		);
		List<WatchlistEntry> entries = List.of(
			new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null)
		);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(repository.findAllByUserIdOrderByAddedAtDescIdDesc(10L)).thenReturn(entries);

		assertThat(service.findByFilters(10L, null)).containsExactlyElementsOf(entries);
	}

	@Test
	void findByFiltersReturnsOnlyMatchingStatus() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			userService,
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry wantToWatch = new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);
		WatchlistEntry watching = new WatchlistEntry(2L, 10L, 30L, WatchStatus.WATCHING, Instant.now(), Instant.now(), Instant.now(), null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(repository.findAllByUserIdOrderByAddedAtDescIdDesc(10L)).thenReturn(List.of(wantToWatch, watching));

		assertThat(service.findByFilters(10L, WatchStatus.WATCHING)).containsExactly(watching);
	}

	@Test
	void findByFiltersRejectsMissingUser() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			userService,
			Mockito.mock(TitleService.class)
		);

		when(userService.findById(10L)).thenThrow(new NoSuchElementException("User not found: 10"));

		assertThatThrownBy(() -> service.findByFilters(10L, null))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("User not found: 10");
	}

	@Test
	void findByIdReturnsEntryForUser() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			userService,
			Mockito.mock(TitleService.class)
		);
		WatchlistEntry entry = new WatchlistEntry(1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, Instant.now(), Instant.now(), null, null);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(repository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.of(entry));

		assertThat(service.findById(10L, 1L)).isEqualTo(entry);
	}

	@Test
	void findByIdRejectsMissingEntryForUser() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		UserService userService = Mockito.mock(UserService.class);
		WatchlistEntryService service = new WatchlistEntryService(
			repository,
			validator,
			userService,
			Mockito.mock(TitleService.class)
		);

		when(userService.findById(10L)).thenReturn(new User(10L, "user@example.com", "Scott", Instant.now(), Instant.now()));
		when(repository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findById(10L, 1L))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("Watchlist entry not found: 1");
	}
}
