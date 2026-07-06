package com.wewatch.api.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.SuggestionImpression;
import com.wewatch.api.repository.projection.SuggestionLastShown;

public interface SuggestionImpressionRepository extends JpaRepository<SuggestionImpression, Long> {

	// The recency penalty is scoped to a set of users (#247): a watchlist's shelf
	// answers to the union of every member's impressions. MAX collapses the per-day
	// rows and shared-list fan-out to the single most recent showing per title.
	@Query("SELECT si.tmdbId AS tmdbId, MAX(si.shownOn) AS shownOn FROM SuggestionImpression si " +
		"WHERE si.userId IN :userIds AND si.shownOn >= :from AND si.shownOn < :to " +
		"GROUP BY si.tmdbId")
	List<SuggestionLastShown> findLastShown(
		@Param("userIds") Collection<Long> userIds,
		@Param("from") LocalDate from,
		@Param("to") LocalDate to
	);

	// One row per (user, title, day), insert-only (#264): re-serving a title on a
	// later day adds a row instead of overwriting, so the most recent previous-day
	// showing stays readable all day and same-day recomputes see stable penalty
	// inputs. Atomic ON CONFLICT so concurrent computes can't race into duplicates.
	@Modifying
	@Query(value = "INSERT INTO suggestion_impressions (user_id, tmdb_id, shown_on) " +
		"VALUES (:userId, :tmdbId, :shownOn) " +
		"ON CONFLICT (user_id, tmdb_id, shown_on) DO NOTHING",
		nativeQuery = true)
	void insertImpression(
		@Param("userId") Long userId,
		@Param("tmdbId") String tmdbId,
		@Param("shownOn") LocalDate shownOn
	);

	@Modifying
	@Query("DELETE FROM SuggestionImpression si WHERE si.userId IN :userIds AND si.shownOn < :cutoff")
	int deleteShownBefore(@Param("userIds") Collection<Long> userIds, @Param("cutoff") LocalDate cutoff);
}
