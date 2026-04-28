package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
		when(repository.create(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User created = service.create(user);

		assertThat(created.getCreatedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).create(user);
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
}
