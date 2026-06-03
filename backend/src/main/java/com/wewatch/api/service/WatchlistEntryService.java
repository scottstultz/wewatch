package com.wewatch.api.service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.exception.DuplicateWatchlistEntryException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistRepository;

@Service
public class WatchlistEntryService {

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final Validator validator;
	private final WatchlistRepository watchlistRepository;
	private final TitleService titleService;

	public WatchlistEntryService(
		WatchlistEntryRepository watchlistEntryRepository,
		Validator validator,
		WatchlistRepository watchlistRepository,
		TitleService titleService
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.validator = validator;
		this.watchlistRepository = watchlistRepository;
		this.titleService = titleService;
	}

	@Transactional
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
		watchlistRepository.findById(watchlistEntry.getWatchlistId())
			.orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + watchlistEntry.getWatchlistId()));
		Title title = titleService.findById(watchlistEntry.getTitleId());
		watchlistEntry.setExternalId(title.getExternalId());
		watchlistEntry.setExternalSource(title.getExternalSource());
		watchlistEntryRepository.findByWatchlistIdAndTitleId(watchlistEntry.getWatchlistId(), watchlistEntry.getTitleId())
			.ifPresent(existingEntry -> {
				throw new DuplicateWatchlistEntryException(
					watchlistEntry.getWatchlistId(),
					watchlistEntry.getTitleId()
				);
			});

		return watchlistEntryRepository.save(watchlistEntry);
	}

	public WatchlistEntry findById(Long watchlistId, Long id) {
		watchlistRepository.findById(watchlistId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + watchlistId));
		return watchlistEntryRepository.findByIdAndWatchlistId(id, watchlistId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist entry not found: " + id));
	}

	public Page<WatchlistEntry> findByFilters(Long watchlistId, WatchStatus status, Pageable pageable) {
		watchlistRepository.findById(watchlistId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + watchlistId));
		return watchlistEntryRepository.findByWatchlistId(watchlistId, status, pageable);
	}

	@Transactional
	public WatchlistEntry update(Long watchlistId, Long id, WatchlistEntry watchlistEntry) {
		WatchlistEntry existingEntry = watchlistEntryRepository.findByIdAndWatchlistId(id, watchlistId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist entry not found: " + id));

		watchlistEntry.setId(existingEntry.getId());
		watchlistEntry.setWatchlistId(existingEntry.getWatchlistId());
		watchlistEntry.setAddedByUserId(existingEntry.getAddedByUserId());
		watchlistEntry.setTitleId(existingEntry.getTitleId());
		watchlistEntry.setExternalId(existingEntry.getExternalId());
		watchlistEntry.setExternalSource(existingEntry.getExternalSource());
		if (watchlistEntry.getAddedAt() == null) {
			watchlistEntry.setAddedAt(existingEntry.getAddedAt());
		}
		if (watchlistEntry.getStartedAt() == null) {
			watchlistEntry.setStartedAt(existingEntry.getStartedAt());
		}
		if (watchlistEntry.getCompletedAt() == null) {
			watchlistEntry.setCompletedAt(existingEntry.getCompletedAt());
		}
		if (watchlistEntry.getStatus() == WatchStatus.WATCHING && watchlistEntry.getStartedAt() == null) {
			watchlistEntry.setStartedAt(Instant.now());
		}
		if (watchlistEntry.getStatus() == WatchStatus.WATCHED) {
			if (watchlistEntry.getStartedAt() == null) {
				watchlistEntry.setStartedAt(Instant.now());
			}
			if (watchlistEntry.getCompletedAt() == null) {
				watchlistEntry.setCompletedAt(Instant.now());
			}
		}

		// Clear timestamps that are incompatible with the target status (handles backward transitions)
		if (watchlistEntry.getStatus() == WatchStatus.WANT_TO_WATCH) {
			watchlistEntry.setStartedAt(null);
			watchlistEntry.setCompletedAt(null);
		} else if (watchlistEntry.getStatus() == WatchStatus.WATCHING) {
			watchlistEntry.setCompletedAt(null);
		}

		watchlistEntry.setUpdatedAt(Instant.now());

		validate(watchlistEntry);
		return watchlistEntryRepository.save(watchlistEntry);
	}

	@Transactional
	public void deleteById(Long watchlistId, Long id) {
		watchlistEntryRepository.deleteByIdAndWatchlistId(id, watchlistId);
	}

	private void validate(WatchlistEntry watchlistEntry) {
		Set<ConstraintViolation<WatchlistEntry>> violations = validator.validate(watchlistEntry);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}
