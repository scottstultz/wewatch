package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import com.wewatch.api.model.WatchlistEntry;

public interface WatchlistEntryRepository {

	WatchlistEntry create(WatchlistEntry watchlistEntry);

	Optional<WatchlistEntry> findById(Long userId, Long id);

	Optional<WatchlistEntry> findByUserIdAndTitleId(Long userId, Long titleId);

	List<WatchlistEntry> findAllByUserId(Long userId);

	WatchlistEntry update(WatchlistEntry watchlistEntry);

	void deleteById(Long userId, Long id);
}
