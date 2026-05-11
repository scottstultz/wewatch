package com.wewatch.api.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import com.wewatch.api.exception.DuplicateWatchlistEntryException;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.WatchlistEntryRepository;

@Service
public class WatchlistEntryService {

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final Validator validator;
	private final UserService userService;
	private final TitleService titleService;

	public WatchlistEntryService(
		WatchlistEntryRepository watchlistEntryRepository,
		Validator validator,
		UserService userService,
		TitleService titleService
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.validator = validator;
		this.userService = userService;
		this.titleService = titleService;
	}

	public WatchlistEntry create(WatchlistEntry watchlistEntry) {
		Instant now = Instant.now();
		if (watchlistEntry.getStatus() == null) {
			watchlistEntry.setStatus(WatchStatus.WANT_TO_WATCH);
		}
		if (watchlistEntry.getAddedAt() == null) {
			watchlistEntry.setAddedAt(now);
		}
		if (watchlistEntry.getUpdatedAt() == null) {
			watchlistEntry.setUpdatedAt(now);
		}
		if (watchlistEntry.getStatus() == WatchStatus.WATCHING && watchlistEntry.getStartedAt() == null) {
			watchlistEntry.setStartedAt(now);
		}
		if (watchlistEntry.getStatus() == WatchStatus.WATCHED) {
			if (watchlistEntry.getStartedAt() == null) {
				watchlistEntry.setStartedAt(now);
			}
			if (watchlistEntry.getCompletedAt() == null) {
				watchlistEntry.setCompletedAt(now);
			}
		}

		validate(watchlistEntry);
		userService.findById(watchlistEntry.getUserId());
		titleService.findById(watchlistEntry.getTitleId());
		watchlistEntryRepository.findByUserIdAndTitleId(watchlistEntry.getUserId(), watchlistEntry.getTitleId())
			.ifPresent(existingEntry -> {
				throw new DuplicateWatchlistEntryException(
					watchlistEntry.getUserId(),
					watchlistEntry.getTitleId()
				);
			});

		return watchlistEntryRepository.save(watchlistEntry);
	}

	public WatchlistEntry findById(Long userId, Long id) {
		userService.findById(userId);
		return watchlistEntryRepository.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist entry not found: " + id));
	}

	public List<WatchlistEntry> findByFilters(Long userId, WatchStatus status) {
		userService.findById(userId);
		List<WatchlistEntry> entries = watchlistEntryRepository.findAllByUserIdOrderByAddedAtDescIdDesc(userId);
		if (status == null) {
			return entries;
		}
		return entries.stream()
			.filter(entry -> entry.getStatus() == status)
			.toList();
	}

	public WatchlistEntry update(Long userId, Long id, WatchlistEntry watchlistEntry) {
		WatchlistEntry existingEntry = watchlistEntryRepository.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist entry not found: " + id));

		watchlistEntry.setId(existingEntry.getId());
		watchlistEntry.setUserId(existingEntry.getUserId());
		if (watchlistEntry.getAddedAt() == null) {
			watchlistEntry.setAddedAt(existingEntry.getAddedAt());
		}
		watchlistEntry.setUpdatedAt(Instant.now());

		validate(watchlistEntry);
		return watchlistEntryRepository.save(watchlistEntry);
	}

	public void deleteById(Long userId, Long id) {
		watchlistEntryRepository.deleteByIdAndUserId(id, userId);
	}

	private void validate(WatchlistEntry watchlistEntry) {
		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(watchlistEntry);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}
