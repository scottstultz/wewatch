package com.wewatch.api.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.WatchlistEntryRepository;

@Service
@Profile("local")
public class WatchlistEntryService {

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final Validator validator;

	public WatchlistEntryService(WatchlistEntryRepository watchlistEntryRepository, Validator validator) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.validator = validator;
	}

	public WatchlistEntry create(WatchlistEntry watchlistEntry) {
		Instant now = Instant.now();
		if (watchlistEntry.getAddedAt() == null) {
			watchlistEntry.setAddedAt(now);
		}
		if (watchlistEntry.getUpdatedAt() == null) {
			watchlistEntry.setUpdatedAt(now);
		}

		validate(watchlistEntry);
		return watchlistEntryRepository.create(watchlistEntry);
	}

	public Optional<WatchlistEntry> findById(Long userId, Long id) {
		return watchlistEntryRepository.findById(userId, id);
	}

	public List<WatchlistEntry> findAllByUserId(Long userId) {
		return watchlistEntryRepository.findAllByUserId(userId);
	}

	public WatchlistEntry update(Long userId, Long id, WatchlistEntry watchlistEntry) {
		WatchlistEntry existingEntry = watchlistEntryRepository.findById(userId, id)
			.orElseThrow(() -> new NoSuchElementException("Watchlist entry not found: " + id));

		watchlistEntry.setId(existingEntry.getId());
		watchlistEntry.setUserId(existingEntry.getUserId());
		if (watchlistEntry.getAddedAt() == null) {
			watchlistEntry.setAddedAt(existingEntry.getAddedAt());
		}
		watchlistEntry.setUpdatedAt(Instant.now());

		validate(watchlistEntry);
		return watchlistEntryRepository.update(watchlistEntry);
	}

	public void deleteById(Long userId, Long id) {
		watchlistEntryRepository.deleteById(userId, id);
	}

	private void validate(WatchlistEntry watchlistEntry) {
		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(watchlistEntry);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}
