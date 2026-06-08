package com.wewatch.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.EpisodeProgress;

public interface EpisodeProgressRepository extends JpaRepository<EpisodeProgress, Long> {

	List<EpisodeProgress> findByWatchlistEntryId(Long watchlistEntryId);

	List<EpisodeProgress> findByWatchlistEntryIdAndSeasonNumber(Long watchlistEntryId, Integer seasonNumber);

	Optional<EpisodeProgress> findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(
		Long watchlistEntryId, Integer seasonNumber, Integer episodeNumber
	);

	long countByWatchlistEntryIdAndWatchedTrue(Long watchlistEntryId);

	@Query("SELECT ep.watchlistEntryId, COUNT(ep), SUM(CASE WHEN ep.watched = true THEN 1 ELSE 0 END) " +
		"FROM EpisodeProgress ep WHERE ep.watchlistEntryId IN :entryIds GROUP BY ep.watchlistEntryId")
	List<Object[]> summarizeByEntryIds(@Param("entryIds") List<Long> entryIds);

	@Query("SELECT ep.watchlistEntryId, ep.seasonNumber, ep.episodeNumber FROM EpisodeProgress ep " +
		"WHERE ep.watched = true AND ep.watchlistEntryId IN :entryIds " +
		"AND ep.watchedAt = (SELECT MAX(ep2.watchedAt) FROM EpisodeProgress ep2 " +
		"WHERE ep2.watchlistEntryId = ep.watchlistEntryId AND ep2.watched = true)")
	List<Object[]> findLastWatchedByEntryIds(@Param("entryIds") List<Long> entryIds);

	@Modifying
	@Query("UPDATE EpisodeProgress ep SET ep.watched = :watched, ep.watchedAt = :watchedAt " +
		"WHERE ep.watchlistEntryId = :entryId AND ep.seasonNumber = :seasonNumber")
	int updateSeasonWatched(
		@Param("entryId") Long entryId,
		@Param("seasonNumber") Integer seasonNumber,
		@Param("watched") boolean watched,
		@Param("watchedAt") Instant watchedAt
	);
}
