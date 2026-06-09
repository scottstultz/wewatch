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
		WITH latest_air AS (
		    SELECT ep.watchlist_entry_id, MAX(ec.air_date) AS latest_air_date
		    FROM episode_progress ep
		    JOIN watchlist_entries we ON we.id = ep.watchlist_entry_id
		    JOIN titles t ON t.id = we.title_id
		    JOIN tmdb_episode_cache ec
		        ON ec.tmdb_id = t.external_id
		       AND ec.season_number = ep.season_number
		       AND ec.episode_number = ep.episode_number
		    WHERE ep.watched = true
		      AND ep.watchlist_entry_id IN (:entryIds)
		      AND ec.air_date IS NOT NULL
		    GROUP BY ep.watchlist_entry_id
		),
		ranked_next AS (
		    SELECT la.watchlist_entry_id,
		           ec.season_number, ec.episode_number,
		           ec.name, ec.air_date, ec.runtime_minutes,
		           ROW_NUMBER() OVER (
		               PARTITION BY la.watchlist_entry_id
		               ORDER BY ec.air_date ASC
		           ) AS rn
		    FROM latest_air la
		    JOIN watchlist_entries we ON we.id = la.watchlist_entry_id
		    JOIN titles t ON t.id = we.title_id
		    JOIN tmdb_episode_cache ec ON ec.tmdb_id = t.external_id
		    WHERE ec.air_date > la.latest_air_date
		      AND ec.air_date IS NOT NULL
		)
		SELECT watchlist_entry_id, season_number, episode_number,
		       name, air_date, runtime_minutes
		FROM ranked_next WHERE rn = 1
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
