package com.wewatch.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.repository.projection.EpisodeProgressCounts;
import com.wewatch.api.repository.projection.LastWatchedEpisode;
import com.wewatch.api.repository.projection.NextEpisode;
import com.wewatch.api.repository.projection.WatchedEpisodeRuntime;

public interface EpisodeProgressRepository extends JpaRepository<EpisodeProgress, Long> {

	List<EpisodeProgress> findByWatchlistEntryId(Long watchlistEntryId);

	List<EpisodeProgress> findByWatchlistEntryIdAndSeasonNumber(Long watchlistEntryId, Integer seasonNumber);

	Optional<EpisodeProgress> findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(
		Long watchlistEntryId, Integer seasonNumber, Integer episodeNumber
	);

	long countByWatchlistEntryIdAndWatchedTrue(Long watchlistEntryId);

	@Query("SELECT ep.watchlistEntryId AS entryId, COUNT(ep) AS total, " +
		"SUM(CASE WHEN ep.watched = true THEN 1 ELSE 0 END) AS watched " +
		"FROM EpisodeProgress ep WHERE ep.watchlistEntryId IN :entryIds GROUP BY ep.watchlistEntryId")
	List<EpisodeProgressCounts> summarizeByEntryIds(@Param("entryIds") List<Long> entryIds);

	@Query("SELECT ep.watchlistEntryId AS entryId, ep.seasonNumber AS seasonNumber, " +
		"ep.episodeNumber AS episodeNumber FROM EpisodeProgress ep " +
		"WHERE ep.watched = true AND ep.watchlistEntryId IN :entryIds " +
		"AND ep.watchedAt = (SELECT MAX(ep2.watchedAt) FROM EpisodeProgress ep2 " +
		"WHERE ep2.watchlistEntryId = ep.watchlistEntryId AND ep2.watched = true)")
	List<LastWatchedEpisode> findLastWatchedByEntryIds(@Param("entryIds") List<Long> entryIds);

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
		SELECT eo.watchlist_entry_id AS entryId, eo.season_number AS seasonNumber,
		       eo.episode_number AS episodeNumber, eo.name AS name,
		       eo.air_date AS airDate, eo.runtime_minutes AS runtimeMinutes
		FROM episode_order eo
		JOIN last_watched_pos lw ON lw.watchlist_entry_id = eo.watchlist_entry_id
		WHERE eo.pos = lw.max_pos + 1
		""")
	List<NextEpisode> findNextEpisodeByEntryIds(@Param("entryIds") List<Long> entryIds);

	/**
	 * One row per watched episode on a watchlist: the show's TMDB id and that episode's runtime
	 * (#323). Feeds both the "episodes finished" count and the TV half of watch time.
	 *
	 * <p>The join to the episode cache is a LEFT JOIN on purpose. An episode the user has ticked
	 * but that has no cached row — a show prewarmed before the episode aired, a gap in the cache —
	 * still produces a row, with a null runtime. It therefore counts as watched and contributes
	 * nothing to the time total, which is exactly the behaviour the issue asks for. An inner join
	 * would drop it from both, under-reporting the count.
	 *
	 * <p>Not filtered on entry status: 40 episodes into a show you are still WATCHING is 40
	 * episodes watched. Only entry-level completion (a finished movie, a finished show) cares
	 * about status, and that is counted from {@code findWatchlistTitles} instead.
	 */
	@Query(nativeQuery = true, value = """
		SELECT t.external_id AS externalId,
		       ec.runtime_minutes AS runtimeMinutes
		FROM episode_progress ep
		JOIN watchlist_entries we ON we.id = ep.watchlist_entry_id
		JOIN titles t ON t.id = we.title_id
		LEFT JOIN tmdb_episode_cache ec
		    ON ec.tmdb_id = t.external_id
		   AND ec.season_number = ep.season_number
		   AND ec.episode_number = ep.episode_number
		WHERE we.watchlist_id = :watchlistId
		  AND ep.watched = true
		""")
	List<WatchedEpisodeRuntime> findWatchedEpisodeRuntimes(@Param("watchlistId") Long watchlistId);

	@Modifying
	@Query("UPDATE EpisodeProgress ep SET ep.watched = :watched, ep.watchedAt = :watchedAt " +
		"WHERE ep.watchlistEntryId = :entryId AND ep.seasonNumber = :seasonNumber")
	int updateSeasonWatched(
		@Param("entryId") Long entryId,
		@Param("seasonNumber") Integer seasonNumber,
		@Param("watched") boolean watched,
		@Param("watchedAt") Instant watchedAt
	);

	@Modifying
	@Query("UPDATE EpisodeProgress ep SET ep.watched = :watched, ep.watchedAt = :watchedAt " +
		"WHERE ep.watchlistEntryId = :entryId AND ep.seasonNumber = :seasonNumber " +
		"AND ep.episodeNumber IN :episodeNumbers")
	int updateEpisodesWatched(
		@Param("entryId") Long entryId,
		@Param("seasonNumber") Integer seasonNumber,
		@Param("episodeNumbers") List<Integer> episodeNumbers,
		@Param("watched") boolean watched,
		@Param("watchedAt") Instant watchedAt
	);
}
