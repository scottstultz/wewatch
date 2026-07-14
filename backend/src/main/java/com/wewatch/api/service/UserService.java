package com.wewatch.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.exception.AccountLinkConflictException;
import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.exception.InvalidCredentialsException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.security.EmailNormalizer;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final Validator validator;
	private final WatchlistService watchlistService;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository userRepository, Validator validator, WatchlistService watchlistService,
			PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.validator = validator;
		this.watchlistService = watchlistService;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public User create(User user) {
		Instant now = Instant.now();
		if (user.getCreatedAt() == null) {
			user.setCreatedAt(now);
		}
		if (user.getUpdatedAt() == null) {
			user.setUpdatedAt(now);
		}
		// Canonicalize before validate() so @Email sees exactly what gets stored.
		user.setEmail(EmailNormalizer.normalize(user.getEmail()));

		validate(user);

		userRepository.findByEmailIgnoreCase(user.getEmail())
			.ifPresent(existingUser -> {
				throw new DuplicateEmailException(user.getEmail());
			});

		User saved = userRepository.save(user);
		watchlistService.provisionPersonalWatchlist(saved.getId(), saved.getDisplayName() + "'s Watchlist");
		return saved;
	}

	public User findById(Long id) {
		return userRepository.findById(id)
			.orElseThrow(() -> new NoSuchElementException("User not found: " + id));
	}

	public Map<Long, User> findByIds(List<Long> ids) {
		return StreamSupport.stream(userRepository.findAllById(ids).spliterator(), false)
			.collect(Collectors.toMap(User::getId, Function.identity()));
	}

	public User findByEmail(String email) {
		return userRepository.findByEmailIgnoreCase(EmailNormalizer.normalize(email))
			.orElseThrow(() -> new NoSuchElementException("No user is registered with that email"));
	}

	// The email is deliberately not updatable (#342). It is the account's identity: the allowlist,
	// sign-in, and provider linking all key on it, and nothing here can prove the caller owns an
	// address they are moving to. Changing one is an admin/DB operation. This also keeps provider_id
	// — a second copy of the address for password users, see registerWithPassword — from going stale.
	public User update(Long id, String displayName) {
		User existingUser = findById(id);

		if (displayName != null) {
			existingUser.setDisplayName(displayName);
		}
		existingUser.setUpdatedAt(Instant.now());

		validate(existingUser);

		return userRepository.save(existingUser);
	}

	// Streaming-service settings (#270). Separate from update(): profile-identity
	// and provider-settings writes have different callers and validation, and
	// null-means-unchanged semantics stay unambiguous per field.
	public User updateStreamingSettings(Long id, String watchRegion, List<Integer> watchProviderIds) {
		User existingUser = findById(id);

		if (watchRegion != null) {
			existingUser.setWatchRegion(watchRegion);
		}
		if (watchProviderIds != null) {
			existingUser.setWatchProviderIds(watchProviderIds);
		}
		existingUser.setUpdatedAt(Instant.now());

		validate(existingUser);
		return userRepository.save(existingUser);
	}

	@Transactional
	public User findOrCreateByProviderIdentity(String provider, String providerId, String email, String displayName) {
		// Whatever casing the provider hands back must resolve to the one account for that
		// address, otherwise which account a Google sign-in links to is casing-dependent.
		String normalizedEmail = EmailNormalizer.normalize(email);
		return userRepository.findByProviderAndProviderId(provider, providerId)
			.orElseGet(() -> {
				Instant now = Instant.now();
				return userRepository.findByEmailIgnoreCase(normalizedEmail)
					.map(existing -> {
						// Trust-on-first-use by email is a pre-hijack vector (#342): anyone who knows an
						// allowlisted address can claim it first (register is allowlist-gated but proves no
						// ownership), and adopting the row here would hand the Google signer an account whose
						// password hash — and watchlist memberships — someone else still holds. Only a row with
						// no credentials at all is safe to adopt: nobody can sign into it today, so linking it
						// grants no one access they did not already have.
						if (existing.getPasswordHash() != null || existing.getProviderId() != null) {
							throw new AccountLinkConflictException();
						}
						existing.setProvider(provider);
						existing.setProviderId(providerId);
						existing.setUpdatedAt(now);
						return userRepository.save(existing);
					})
					.orElseGet(() -> {
						String name = (displayName != null && !displayName.isBlank()) ? displayName : normalizedEmail;
						User saved = userRepository.save(
							new User(null, normalizedEmail, name, now, now, provider, providerId));
						watchlistService.provisionPersonalWatchlist(saved.getId(), saved.getDisplayName() + "'s Watchlist");
						return saved;
					});
			});
	}

	@Transactional
	public User registerWithPassword(String email, String displayName, String password) {
		String normalizedEmail = EmailNormalizer.normalize(email);
		userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(existing -> {
			throw new DuplicateEmailException(normalizedEmail);
		});

		Instant now = Instant.now();
		User user = new User();
		user.setEmail(normalizedEmail);
		user.setDisplayName(displayName);
		user.setProvider("email");
		// providerId is a second copy of the address for password users — canonicalize it too,
		// or findByProviderAndProviderId goes stale against the normalized email column.
		user.setProviderId(normalizedEmail);
		user.setPasswordHash(passwordEncoder.encode(password));
		user.setCreatedAt(now);
		user.setUpdatedAt(now);

		validate(user);
		User saved = userRepository.save(user);
		watchlistService.provisionPersonalWatchlist(saved.getId(), saved.getDisplayName() + "'s Watchlist");
		return saved;
	}

	/**
	 * Valid BCrypt hash of a random unguessable value, compared against when the email is
	 * unknown or has no password hash. Without this, the not-found path skips the ~100ms
	 * BCrypt check and response timing reveals which emails are registered.
	 */
	private static final String DUMMY_PASSWORD_HASH = "$2a$10$ntarrN47RrxhmC4J/OvMQe2PSTM.z92G7iVpW9aIgvFDCdVZxIeYO";

	public User authenticateWithPassword(String email, String password) {
		User user = userRepository.findByEmailIgnoreCase(EmailNormalizer.normalize(email))
			.filter(u -> u.getPasswordHash() != null)
			.orElse(null);

		String hash = user != null ? user.getPasswordHash() : DUMMY_PASSWORD_HASH;
		boolean matches = passwordEncoder.matches(password, hash);
		if (user == null || !matches) {
			throw new InvalidCredentialsException();
		}
		return user;
	}

	private void validate(User user) {
		Set<ConstraintViolation<User>> violations = validator.validate(user);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}
