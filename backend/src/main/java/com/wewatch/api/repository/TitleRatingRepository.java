package com.wewatch.api.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.TitleRating;

public interface TitleRatingRepository extends JpaRepository<TitleRating, Long> {

	// One user's ratings for a batch of titles — feeds the myRating field on
	// entry/detail responses in a single read per request
	@Query("SELECT tr FROM TitleRating tr WHERE tr.userId = :userId AND tr.titleId IN :titleIds")
	List<TitleRating> findByUserIdAndTitleIds(
		@Param("userId") Long userId,
		@Param("titleIds") Collection<Long> titleIds
	);

	// All members' ratings for a watchlist's titles — the suggestion profile
	// folds in every member (#273, matching the #247 user-scoping rule)
	@Query("SELECT tr FROM TitleRating tr WHERE tr.userId IN :userIds AND tr.titleId IN :titleIds")
	List<TitleRating> findByUserIdsAndTitleIds(
		@Param("userIds") Collection<Long> userIds,
		@Param("titleIds") Collection<Long> titleIds
	);

	// Atomic upsert: re-rating flips the rating in place (double tap, stale
	// client, changed mind) while keeping the original created_at
	@Modifying
	@Query(value = "INSERT INTO title_ratings (user_id, title_id, rating, created_at, updated_at) " +
		"VALUES (:userId, :titleId, :rating, :now, :now) " +
		"ON CONFLICT (user_id, title_id) " +
		"DO UPDATE SET rating = EXCLUDED.rating, updated_at = EXCLUDED.updated_at",
		nativeQuery = true)
	void upsertRating(
		@Param("userId") Long userId,
		@Param("titleId") Long titleId,
		@Param("rating") String rating,
		@Param("now") Instant now
	);

	@Modifying
	@Query("DELETE FROM TitleRating tr WHERE tr.userId = :userId AND tr.titleId = :titleId")
	int deleteByUserIdAndTitleId(@Param("userId") Long userId, @Param("titleId") Long titleId);
}
