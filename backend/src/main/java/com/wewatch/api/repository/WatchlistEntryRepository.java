package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import com.wewatch.api.model.WatchlistEntry;

public interface WatchlistEntryRepository {

	WatchlistEntry create(WatchlistEntry watchlistEntry);

	Optional<WatchlistEntry> findById(Long id);

	List<WatchlistEntry> findAll();

	WatchlistEntry update(WatchlistEntry watchlistEntry);

	void deleteById(Long id);
}
