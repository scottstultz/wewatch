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

	@Query(nativeQuery = true, value = """
		WITH episode_order AS (
		    SELECT we.id AS watchlist_entry_id,
		           ec.season_number, ec.episode_number,
		           ec.name, ec.air_date, ec.runtime_minutes,
		           ROW_NUMBER() OVER (
		               PARTITION BY we.id
		               ORDER BY ec.air_date ASC NULLS LAST,
		                        ec.season_number ASC,
		                        ec.episode_number ASC
		           ) AS pos
		    FROM watchlist_entries we
		    JOIN titles t ON t.id = we.title_id
		    JOIN tmdb_episode_cache ec ON ec.tmdb_id = t.external_id
		    WHERE we.id IN (:entryIds)
		),
		last_watched_pos AS (
		    SELECT eo.watchlist_entry_id, MAX(eo.pos) AS max_pos
		    FROM episode_order eo
		    JOIN episode_progress ep
		        ON ep.watchlist_entry_id = eo.watchlist_entry_id
		       AND ep.season_number = eo.season_number
		       AND ep.episode_number = eo.episode_number
		    WHERE ep.watched = true
		    GROUP BY eo.watchlist_entry_id
		)
		SELECT eo.watchlist_entry_id, eo.season_number, eo.episode_number,
		       eo.name, eo.air_date, eo.runtime_minutes
		FROM episode_order eo
		JOIN last_watched_pos lw ON lw.watchlist_entry_id = eo.watchlist_entry_id
		WHERE eo.pos = lw.max_pos + 1
		""")
	List<Object[]> findNextEpisodeByEntryIds(@Param("entryIds") List<Long> entryIds);

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
