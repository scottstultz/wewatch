package com.wewatch.api.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.repository.projection.ReturningEpisode;

public interface TmdbEpisodeCacheRepository extends JpaRepository<TmdbEpisodeCache, Long> {

	List<TmdbEpisodeCache> findByTmdbIdAndSeasonNumber(String tmdbId, Integer seasonNumber);

	/**
	 * Every cached episode airing in [from, to] for a TV entry on this watchlist —
	 * any status, not just WATCHING (#416) — earliest first (#321). BETWEEN is
	 * inclusive at both ends, which is the window semantics the feature wants, and
	 * it drops null air dates for free.
	 *
	 * <p>Returns <em>all</em> matching episodes, not one per show: the caller picks
	 * the earliest per entry in Java. That is deliberate. This suite mocks every
	 * repository — no test executes SQL — so a ROW_NUMBER() CTE doing the pick here
	 * would be logic no test could reach.
	 */
	@Query(nativeQuery = true, value = """
		SELECT we.id AS entryId,
		       t.external_id AS externalId,
		       t.external_source AS externalSource,
		       t.name AS showName,
		       t.poster_url AS posterUrl,
		       ec.season_number AS seasonNumber,
		       ec.episode_number AS episodeNumber,
		       ec.name AS episodeName,
		       ec.air_date AS airDate,
		       ec.runtime_minutes AS runtimeMinutes
		FROM watchlist_entries we
		JOIN titles t ON t.id = we.title_id
		JOIN tmdb_episode_cache ec ON ec.tmdb_id = 'tv:' || t.external_id
		WHERE we.watchlist_id = :watchlistId
		  AND t.type = 'TV'
		  AND ec.air_date BETWEEN :from AND :to
		ORDER BY ec.air_date ASC, ec.season_number ASC, ec.episode_number ASC
		""")
	List<ReturningEpisode> findUpcomingByWatchlistId(
		@Param("watchlistId") Long watchlistId,
		@Param("from") LocalDate from,
		@Param("to") LocalDate to
	);
}
