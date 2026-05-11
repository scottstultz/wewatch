package com.wewatch.api.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.UserRepository;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final Validator validator;

	public UserService(UserRepository userRepository, Validator validator) {
		this.userRepository = userRepository;
		this.validator = validator;
	}

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

		return userRepository.save(user);
	}

	public User findById(Long id) {
		return userRepository.findById(id)
			.orElseThrow(() -> new NoSuchElementException("User not found: " + id));
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
