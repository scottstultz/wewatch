package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.WatchlistEntry;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, Long> {

	Optional<WatchlistEntry> findByIdAndUserId(Long id, Long userId);

	Optional<WatchlistEntry> findByUserIdAndTitleId(Long userId, Long titleId);

	List<WatchlistEntry> findAllByUserIdOrderByAddedAtDescIdDesc(Long userId);

	void deleteByIdAndUserId(Long id, Long userId);
}
