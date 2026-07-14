package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.exception.InvalidCredentialsException;
import com.wewatch.api.model.User;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistType;
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
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(null, "user@example.com", "Scott", null, null);

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User created = service.create(user);

		assertThat(created.getCreatedAt()).isNotNull();
		assertThat(created.getUpdatedAt()).isNotNull();
		verify(repository).save(user);
	}

	@Test
	void createRejectsInvalidUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(null, "", "Scott", Instant.now(), Instant.now());

		assertThatThrownBy(() -> service.create(user)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void createRejectsDuplicateEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		User user = new User(null, "user@example.com", "Sam", Instant.now(), Instant.now());

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.create(user)).isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void findByIdReturnsPersistedUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));

		assertThat(service.findById(1L)).isEqualTo(existing);
	}

	@Test
	void updateAppliesProvidedFieldsOnly() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
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
	void updateStreamingSettingsAppliesProvidedFieldsOnly() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.EPOCH, Instant.EPOCH);
		existing.setWatchProviderIds(java.util.List.of(8));

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// Region only: provider ids stay as they were (#270)
		User updated = service.updateStreamingSettings(1L, "US", null);

		assertThat(updated.getWatchRegion()).isEqualTo("US");
		assertThat(updated.getWatchProviderIds()).containsExactly(8);
		verify(repository).save(existing);
	}

	@Test
	void updateStreamingSettingsClearsProvidersWithAnEmptyList() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.EPOCH, Instant.EPOCH);
		existing.setWatchRegion("US");
		existing.setWatchProviderIds(java.util.List.of(8, 9));

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// Empty list = explicit clear — turns provider-aware behavior off (#270)
		User updated = service.updateStreamingSettings(1L, null, java.util.List.of());

		assertThat(updated.getWatchRegion()).isEqualTo("US");
		assertThat(updated.getWatchProviderIds()).isEmpty();
	}

	@Test
	void updateRejectsMissingUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(42L, null, "Scott"))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessage("User not found: 42");
	}

	@Test
	void updateRejectsInvalidMergedUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.update(1L, "", null)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void updateRejectsDuplicateEmailForAnotherUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		User other = new User(2L, "other@example.com", "Sam", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmailIgnoreCase("other@example.com")).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.update(1L, "other@example.com", null))
			.isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void updateAllowsExistingEmailForSameUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		User updated = service.update(1L, "user@example.com", "Scott Stultz");

		assertThat(updated.getEmail()).isEqualTo("user@example.com");
		assertThat(updated.getDisplayName()).isEqualTo("Scott Stultz");
		verify(repository).save(existing);
	}

	@Test
	void createProvisionesPersonalWatchlist() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(null, "user@example.com", "Scott", null, null);
		User savedUser = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		Watchlist watchlist = new Watchlist(1L, "Scott's Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenReturn(savedUser);
		when(watchlistService.provisionPersonalWatchlist(1L, "Scott's Watchlist")).thenReturn(watchlist);

		service.create(user);

		verify(watchlistService).provisionPersonalWatchlist(1L, "Scott's Watchlist");
	}

	@Test
	void findOrCreateByProviderIdentityProvisionesWatchlistForNewUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User savedUser = new User(1L, "new@example.com", "New User", Instant.now(), Instant.now());

		when(repository.findByProviderAndProviderId("google", "sub-new")).thenReturn(Optional.empty());
		when(repository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenReturn(savedUser);

		service.findOrCreateByProviderIdentity("google", "sub-new", "new@example.com", "New User");

		verify(watchlistService).provisionPersonalWatchlist(1L, "New User's Watchlist");
	}

	@Test
	void findOrCreateByProviderIdentityDoesNotProvisionWatchlistForExistingUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existingUser = new User(1L, "existing@example.com", "Existing User", Instant.now(), Instant.now());

		when(repository.findByProviderAndProviderId("google", "sub-existing")).thenReturn(Optional.of(existingUser));

		service.findOrCreateByProviderIdentity("google", "sub-existing", "existing@example.com", "Existing User");

		Mockito.verifyNoInteractions(watchlistService);
	}

	@Test
	void findOrCreateByProviderIdentityDoesNotProvisionWatchlistWhenLinkingExistingAccount() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existingUser = new User(1L, "existing@example.com", "Existing User", Instant.now(), Instant.now());

		when(repository.findByProviderAndProviderId("google", "sub-new")).thenReturn(Optional.empty());
		when(repository.findByEmailIgnoreCase("existing@example.com")).thenReturn(Optional.of(existingUser));
		when(repository.save(any(User.class))).thenReturn(existingUser);

		service.findOrCreateByProviderIdentity("google", "sub-new", "existing@example.com", "Existing User");

		Mockito.verifyNoInteractions(watchlistService);
	}

	// ── registerWithPassword tests ───────────────────────────

	@Test
	void registerWithPasswordCreatesUserWithHashedPassword() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
		when(passwordEncoder.encode("password123")).thenReturn("$2a$hashed");
		when(repository.save(any(User.class))).thenAnswer(invocation -> {
			User u = invocation.getArgument(0);
			u.setId(1L);
			return u;
		});

		User result = service.registerWithPassword("new@example.com", "New User", "password123");

		assertThat(result.getEmail()).isEqualTo("new@example.com");
		assertThat(result.getDisplayName()).isEqualTo("New User");
		assertThat(result.getProvider()).isEqualTo("email");
		assertThat(result.getProviderId()).isEqualTo("new@example.com");
		assertThat(result.getPasswordHash()).isEqualTo("$2a$hashed");
		verify(passwordEncoder).encode("password123");
		verify(watchlistService).provisionPersonalWatchlist(1L, "New User's Watchlist");
	}

	@Test
	void registerWithPasswordRejectsDuplicateEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "existing@example.com", "Existing", Instant.now(), Instant.now());

		when(repository.findByEmailIgnoreCase("existing@example.com")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.registerWithPassword("existing@example.com", "User", "password123"))
			.isInstanceOf(DuplicateEmailException.class);
	}

	// ── authenticateWithPassword tests ───────────────────────

	@Test
	void authenticateWithPasswordReturnsUserForCorrectPassword() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(1L, "user@example.com", "User", Instant.now(), Instant.now(), "email", "user@example.com");
		user.setPasswordHash("$2a$hashed");

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("password123", "$2a$hashed")).thenReturn(true);

		User result = service.authenticateWithPassword("user@example.com", "password123");
		assertThat(result).isEqualTo(user);
	}

	@Test
	void authenticateWithPasswordThrowsForWrongPassword() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(1L, "user@example.com", "User", Instant.now(), Instant.now(), "email", "user@example.com");
		user.setPasswordHash("$2a$hashed");

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrongpassword", "$2a$hashed")).thenReturn(false);

		assertThatThrownBy(() -> service.authenticateWithPassword("user@example.com", "wrongpassword"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void authenticateWithPasswordThrowsForNonexistentEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.authenticateWithPassword("nobody@example.com", "password123"))
			.isInstanceOf(InvalidCredentialsException.class);

		// dummy compare must run so timing doesn't reveal whether the email is registered (#215)
		verify(passwordEncoder).matches(eq("password123"), anyString());
	}

	@Test
	void authenticateWithPasswordThrowsForGoogleOnlyUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User googleUser = new User(1L, "google@example.com", "Google User", Instant.now(), Instant.now(), "google", "g-sub-123");
		// passwordHash is null — Google-only user

		when(repository.findByEmailIgnoreCase("google@example.com")).thenReturn(Optional.of(googleUser));

		assertThatThrownBy(() -> service.authenticateWithPassword("google@example.com", "password123"))
			.isInstanceOf(InvalidCredentialsException.class);

		// dummy compare must run so timing doesn't reveal whether the account has a password (#215)
		verify(passwordEncoder).matches(eq("password123"), anyString());
	}

	@Test
	void authenticateWithPasswordThrowsForNonexistentEmailEvenIfDummyCompareMatches() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
		when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

		assertThatThrownBy(() -> service.authenticateWithPassword("nobody@example.com", "password123"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	// ── email casing (#345) ──────────────────────────────────
	//
	// Every case below fails against the pre-#345 exact-match lookups: the mocks stub only the
	// canonical key, so a service that passes the raw string through gets Optional.empty() back.

	@Test
	void registerWithPasswordStoresCanonicalEmailAndProviderId() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
		when(passwordEncoder.encode("password123")).thenReturn("$2a$hashed");
		when(repository.save(any(User.class))).thenAnswer(invocation -> {
			User u = invocation.getArgument(0);
			u.setId(1L);
			return u;
		});

		User result = service.registerWithPassword("  New@Example.COM  ", "New User", "password123");

		assertThat(result.getEmail()).isEqualTo("new@example.com");
		// providerId is the second copy of the address for password users — it must match.
		assertThat(result.getProviderId()).isEqualTo("new@example.com");
	}

	@Test
	void registerWithPasswordRejectsCaseVariantOfExistingEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "existing@example.com", "Existing", Instant.now(), Instant.now());

		when(repository.findByEmailIgnoreCase("existing@example.com")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.registerWithPassword("Existing@Example.com", "User", "password123"))
			.isInstanceOf(DuplicateEmailException.class);
		verify(repository, never()).save(any(User.class));
	}

	@Test
	void authenticateWithPasswordAcceptsMixedCaseEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(1L, "user@example.com", "User", Instant.now(), Instant.now(), "email", "user@example.com");
		user.setPasswordHash("$2a$hashed");

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("password123", "$2a$hashed")).thenReturn(true);

		// Registered as user@, signing in as USER@ — same human, same account.
		assertThat(service.authenticateWithPassword("USER@Example.com", "password123")).isEqualTo(user);
	}

	@Test
	void findOrCreateByProviderIdentityLinksMixedCaseProviderEmailToExistingAccount() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "existing@example.com", "Existing User", Instant.now(), Instant.now());

		when(repository.findByProviderAndProviderId("google", "sub-new")).thenReturn(Optional.empty());
		when(repository.findByEmailIgnoreCase("existing@example.com")).thenReturn(Optional.of(existing));
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// Google hands back Existing@Example.com; that must link onto the one account for the
		// address, not mint a second one whose identity depends on the casing Google chose.
		User linked = service.findOrCreateByProviderIdentity(
			"google", "sub-new", "Existing@Example.com", "Existing User");

		assertThat(linked.getId()).isEqualTo(1L);
		assertThat(linked.getProviderId()).isEqualTo("sub-new");
		Mockito.verifyNoInteractions(watchlistService);
	}

	@Test
	void findOrCreateByProviderIdentityStoresCanonicalEmailForNewUser() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);

		when(repository.findByProviderAndProviderId("google", "sub-new")).thenReturn(Optional.empty());
		when(repository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenAnswer(invocation -> {
			User u = invocation.getArgument(0);
			u.setId(1L);
			return u;
		});

		User created = service.findOrCreateByProviderIdentity("google", "sub-new", "New@Example.com", "New User");

		assertThat(created.getEmail()).isEqualTo("new@example.com");
	}

	@Test
	void updateStoresCanonicalEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmailIgnoreCase("moved@example.com")).thenReturn(Optional.empty());
		when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.update(1L, "Moved@Example.com", null).getEmail()).isEqualTo("moved@example.com");
	}

	@Test
	void updateRejectsCaseVariantOfAnotherUsersEmail() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User existing = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());
		User other = new User(2L, "other@example.com", "Sam", Instant.now(), Instant.now());

		when(repository.findById(1L)).thenReturn(Optional.of(existing));
		when(repository.findByEmailIgnoreCase("other@example.com")).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.update(1L, "OTHER@example.com", null))
			.isInstanceOf(DuplicateEmailException.class);
		verify(repository, never()).save(any(User.class));
	}

	@Test
	void findByEmailResolvesMixedCaseInput() {
		UserRepository repository = Mockito.mock(UserRepository.class);
		WatchlistService watchlistService = Mockito.mock(WatchlistService.class);
		PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
		UserService service = new UserService(repository, validator, watchlistService, passwordEncoder);
		User user = new User(1L, "user@example.com", "Scott", Instant.now(), Instant.now());

		when(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

		// The watchlist member-add path (WatchlistController.addMember) lands here.
		assertThat(service.findByEmail(" User@Example.com ")).isEqualTo(user);
	}
}
