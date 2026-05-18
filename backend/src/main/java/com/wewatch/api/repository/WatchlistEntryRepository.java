package com.wewatch.api.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, Long> {

	Optional<WatchlistEntry> findByIdAndUserId(Long id, Long userId);

	Optional<WatchlistEntry> findByUserIdAndTitleId(Long userId, Long titleId);

	@Query("SELECT w FROM WatchlistEntry w WHERE w.userId = :userId AND (:status IS NULL OR w.status = :status) ORDER BY w.addedAt DESC, w.id DESC")
	Page<WatchlistEntry> findByUserId(@Param("userId") Long userId, @Param("status") WatchStatus status, Pageable pageable);

	void deleteByIdAndUserId(Long id, Long userId);
}
