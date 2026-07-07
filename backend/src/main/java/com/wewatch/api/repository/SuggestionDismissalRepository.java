package com.wewatch.api.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.SuggestionDismissal;

public interface SuggestionDismissalRepository extends JpaRepository<SuggestionDismissal, Long> {

	// Dismissals scope to a set of users like the recency penalty (#247/#268): a
	// shared list's shelves exclude the union of every member's dismissals
	@Query("SELECT sd.tmdbId FROM SuggestionDismissal sd WHERE sd.userId IN :userIds")
	List<String> findTmdbIdsByUserIds(@Param("userIds") Collection<Long> userIds);

	// Atomic ON CONFLICT so re-dismissing (double tap, stale client) is a no-op
	// and keeps the original dismissed_at
	@Modifying
	@Query(value = "INSERT INTO suggestion_dismissals (user_id, tmdb_id, dismissed_at) " +
		"VALUES (:userId, :tmdbId, :dismissedAt) " +
		"ON CONFLICT (user_id, tmdb_id) DO NOTHING",
		nativeQuery = true)
	void insertDismissal(
		@Param("userId") Long userId,
		@Param("tmdbId") String tmdbId,
		@Param("dismissedAt") Instant dismissedAt
	);

	@Modifying
	@Query("DELETE FROM SuggestionDismissal sd WHERE sd.userId = :userId AND sd.tmdbId = :tmdbId")
	int deleteByUserIdAndTmdbId(@Param("userId") Long userId, @Param("tmdbId") String tmdbId);
}
