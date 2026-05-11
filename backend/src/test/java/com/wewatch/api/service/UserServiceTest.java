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

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.UserRepository;

class UserServiceTest {

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
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User user = new User(null, "user@example.com", "Scott", null, null);

		when(repository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User created = service.create(user);

		assertThat(created.getCreatedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).save(user);
	}

	@Test
	void createRejectsInvalidUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User user = new User(null, "", "Scott", Instant.now(), Instant.now());

		assertThatThrownBy(() -> service.create(user)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void createRejectsDuplicateEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		User user = new User(null, "user@example.com", "Sam", Instant.now(), Instant.now());

		when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.create(user)).isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void findByIdReturnsPersistedUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));

		assertThat(service.findById(1L)).isEqualTo(existing);
	}

	@Test
	void updateAppliesProvidedFieldsOnly() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		User existing = new User(1L, "user@example.com", "Scott", createdAt, createdAt);

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User updated = service.update(1L, null, "Scott Stultz");

		assertThat(updated.getEmail()).isEqualTo("user@example.com");
		assertThat(updated.getDisplayName()).isEqualTo("Scott Stultz");
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getUpdatedAt()).isAfter(createdAt);
		verify(repository).save(existing);
	}

	@Test
	void updateRejectsMissingUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);

		when(repository.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(42L, null, "Scott"))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("User not found: 42");
	}

	@Test
	void updateRejectsInvalidMergedUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.update(1L, "", null)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void updateRejectsDuplicateEmailForAnotherUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		User other = new User(2L, "other@example.com", "Sam", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmail("other@example.com")).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.update(1L, "other@example.com", null))
			.isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void updateAllowsExistingEmailForSameUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User updated = service.update(1L, "user@example.com", "Scott Stultz");

		assertThat(updated.getEmail()).isEqualTo("user@example.com");
		assertThat(updated.getDisplayName()).isEqualTo("Scott Stultz");
		verify(repository).save(existing);
	}

	@Test
	void findByFiltersReturnsMatchingUsers() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findByFilters("user@example.com", "Scott")).thenReturn(List.of(existing));

		assertThat(service.findByFilters("user@example.com", "Scott")).containsExactly(existing);
		verify(repository).findByFilters("user@example.com", "Scott");
	}

	@Test
	void findByFiltersNormalizesBlankValues() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		UserService service = new UserService(repository, validator);

		when(repository.findByFilters(null, null)).thenReturn(List.of());

		assertThat(service.findByFilters("", " ")).isEmpty();
		verify(repository).findByFilters(null, null);
	}
}
