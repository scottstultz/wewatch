package com.wewatch.api.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.SuggestionImpression;

public interface SuggestionImpressionRepository extends JpaRepository<SuggestionImpression, Long> {

	// Suppression is scoped to a set of users (#247): a watchlist's shelf suppresses
	// the union of every member's impressions, so a shared list draws on all members.
	@Query("SELECT si.tmdbId FROM SuggestionImpression si " +
		"WHERE si.userId IN :userIds AND si.lastShownAt >= :from AND si.lastShownAt < :to")
	List<String> findShownTmdbIds(
		@Param("userIds") Collection<Long> userIds,
		@Param("from") Instant from,
		@Param("to") Instant to
	);

	// Atomic upsert keyed on (user_id, tmdb_id): concurrent computes across a user's
	// watchlists can't race into duplicate rows
	@Modifying
	@Query(value = "INSERT INTO suggestion_impressions (user_id, tmdb_id, last_shown_at) " +
		"VALUES (:userId, :tmdbId, :shownAt) " +
		"ON CONFLICT (user_id, tmdb_id) DO UPDATE SET last_shown_at = EXCLUDED.last_shown_at",
		nativeQuery = true)
	void upsertImpression(
		@Param("userId") Long userId,
		@Param("tmdbId") String tmdbId,
		@Param("shownAt") Instant shownAt
	);

	@Modifying
	@Query("DELETE FROM SuggestionImpression si WHERE si.userId IN :userIds AND si.lastShownAt < :cutoff")
	int deleteShownBefore(@Param("userIds") Collection<Long> userIds, @Param("cutoff") Instant cutoff);
}
