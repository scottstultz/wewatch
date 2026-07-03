package com.wewatch.api.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.SuggestionImpression;

public interface SuggestionImpressionRepository extends JpaRepository<SuggestionImpression, Long> {

	@Query("SELECT si.tmdbId FROM SuggestionImpression si " +
		"WHERE si.watchlistId = :watchlistId AND si.lastShownAt >= :from AND si.lastShownAt < :to")
	List<String> findShownTmdbIds(
		@Param("watchlistId") Long watchlistId,
		@Param("from") Instant from,
		@Param("to") Instant to
	);

	// Atomic upsert keyed on (watchlist_id, tmdb_id): concurrent computes for the
	// same watchlist can't race into duplicate rows
	@Modifying
	@Query(value = "INSERT INTO suggestion_impressions (watchlist_id, tmdb_id, last_shown_at) " +
		"VALUES (:watchlistId, :tmdbId, :shownAt) " +
		"ON CONFLICT (watchlist_id, tmdb_id) DO UPDATE SET last_shown_at = EXCLUDED.last_shown_at",
		nativeQuery = true)
	void upsertImpression(
		@Param("watchlistId") Long watchlistId,
		@Param("tmdbId") String tmdbId,
		@Param("shownAt") Instant shownAt
	);

	@Modifying
	@Query("DELETE FROM SuggestionImpression si WHERE si.watchlistId = :watchlistId AND si.lastShownAt < :cutoff")
	int deleteShownBefore(@Param("watchlistId") Long watchlistId, @Param("cutoff") Instant cutoff);
}
