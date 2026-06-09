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

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.exception.InvalidCredentialsException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.UserRepository;

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

		validate(user);

		userRepository.findByEmail(user.getEmail())
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
		return userRepository.findByEmail(email)
			.orElseThrow(() -> new NoSuchElementException("User not found with email: " + email));
	}

	public User update(Long id, String email, String displayName) {
		User existingUser = findById(id);

		if (email != null) {
			existingUser.setEmail(email);
		}
		if (displayName != null) {
			existingUser.setDisplayName(displayName);
		}
		existingUser.setUpdatedAt(Instant.now());

		validate(existingUser);

		if (email != null) {
			userRepository.findByEmail(email)
				.filter(userWithEmail -> !userWithEmail.getId().equals(id))
				.ifPresent(userWithEmail -> {
					throw new DuplicateEmailException(email);
				});
		}

		return userRepository.save(existingUser);
	}

	@Transactional
	public User findOrCreateByProviderIdentity(String provider, String providerId, String email, String displayName) {
		return userRepository.findByProviderAndProviderId(provider, providerId)
			.orElseGet(() -> {
				Instant now = Instant.now();
				return userRepository.findByEmail(email)
					.map(existing -> {
						existing.setProvider(provider);
						existing.setProviderId(providerId);
						existing.setUpdatedAt(now);
						return userRepository.save(existing);
					})
					.orElseGet(() -> {
						String name = (displayName != null && !displayName.isBlank()) ? displayName : email;
						User saved = userRepository.save(new User(null, email, name, now, now, provider, providerId));
						watchlistService.provisionPersonalWatchlist(saved.getId(), saved.getDisplayName() + "'s Watchlist");
						return saved;
					});
			});
	}

	@Transactional
	public User registerWithPassword(String email, String displayName, String password) {
		userRepository.findByEmail(email).ifPresent(existing -> {
			throw new DuplicateEmailException(email);
		});

		Instant now = Instant.now();
		User user = new User();
		user.setEmail(email);
		user.setDisplayName(displayName);
		user.setProvider("email");
		user.setProviderId(email);
		user.setPasswordHash(passwordEncoder.encode(password));
		user.setCreatedAt(now);
		user.setUpdatedAt(now);

		validate(user);
		User saved = userRepository.save(user);
		watchlistService.provisionPersonalWatchlist(saved.getId(), saved.getDisplayName() + "'s Watchlist");
		return saved;
	}

	public User authenticateWithPassword(String email, String password) {
		User user = userRepository.findByEmail(email)
			.filter(u -> u.getPasswordHash() != null)
			.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		return user;
	}

	public List<User> findByFilters(String email, String displayName) {
		return userRepository.findByFilters(normalize(email), normalize(displayName));
	}

	private void validate(User user) {
		Set<ConstraintViolation<User>> violations = validator.validate(user);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}

	private String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
	}
}
