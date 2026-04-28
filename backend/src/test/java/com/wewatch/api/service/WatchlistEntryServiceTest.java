package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
		WatchlistEntryService service = new WatchlistEntryService(repository, validator);
		WatchlistEntry entry = new WatchlistEntry(null, "Arrival", WatchStatus.WANT_TO_WATCH, null, null, null, null);

		when(repository.create(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry created = service.create(entry);

		assertThat(created.getDateAdded()).isNotNull();
		verify(repository).create(entry);
	}

	@Test
	void createRejectsInvalidEntry() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator);
		WatchlistEntry entry = new WatchlistEntry(null, "", WatchStatus.WANT_TO_WATCH, null, null, Instant.now(), null);

		assertThatThrownBy(() -> service.create(entry)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void updatePreservesOriginalDateAddedWhenOmitted() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator);
		Instant originalDateAdded = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry existingEntry = new WatchlistEntry(
			1L,
			"Arrival",
			WatchStatus.WANT_TO_WATCH,
			null,
			null,
			originalDateAdded,
			null
		);
		WatchlistEntry updatedEntry = new WatchlistEntry(
			null,
			"Arrival",
			WatchStatus.WATCHED,
			5,
			"Great movie.",
			null,
			LocalDate.parse("2026-04-27")
		);

		when(repository.findById(1L)).thenReturn(Optional.of(existingEntry));
		when(repository.update(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

		WatchlistEntry result = service.update(1L, updatedEntry);

		assertThat(result.getId()).isEqualTo(1L);
		assertThat(result.getDateAdded()).isEqualTo(originalDateAdded);
		verify(repository).update(updatedEntry);
	}

	@Test
	void findAllDelegatesToRepository() {
		WatchlistEntryRepository repository = Mockito.mock(WatchlistEntryRepository.class);
		WatchlistEntryService service = new WatchlistEntryService(repository, validator);
		List<WatchlistEntry> entries = List.of(
			new WatchlistEntry(1L, "Arrival", WatchStatus.WANT_TO_WATCH, null, null, Instant.now(), null)
		);

		when(repository.findAll()).thenReturn(entries);

		assertThat(service.findAll()).containsExactlyElementsOf(entries);
	}
}
