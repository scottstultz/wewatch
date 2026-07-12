package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.projection.WatchlistTitle;

public interface TitleRepository extends JpaRepository<Title, Long> {

	Optional<Title> findByExternalSourceAndExternalId(String externalSource, String externalId);

	@Query("SELECT t FROM Title t WHERE (:externalId IS NULL OR t.externalId = :externalId) AND (:externalSource IS NULL OR t.externalSource = :externalSource) AND (:type IS NULL OR t.type = :type) AND (:name IS NULL OR t.name = :name)")
	Page<Title> findByFilters(@Param("externalId") String externalId, @Param("externalSource") String externalSource, @Param("type") TitleType type, @Param("name") String name, Pageable pageable);

	@Query("SELECT DISTINCT t.externalId FROM Title t WHERE t.type = :type AND t.externalId NOT IN (SELECT c.tmdbId FROM TmdbTitleCache c)")
	List<String> findExternalIdsByTypeNotInCache(@Param("type") TitleType type);

	/**
	 * TMDB ids of every TV show someone is actively watching — the set the nightly
	 * episode-cache refresh keeps current (#321). Native because WatchlistEntry holds a
	 * bare titleId column with no association to join across in JPQL.
	 */
	@Query(nativeQuery = true, value = """
		SELECT DISTINCT t.external_id
		FROM titles t
		JOIN watchlist_entries we ON we.title_id = t.id
		WHERE t.type = 'TV' AND we.status = 'WATCHING'
		""")
	List<String> findWatchingTvExternalIds();

	/**
	 * Every entry on a watchlist as (title, medium, status) — the input to the stats page (#323).
	 * Native for the same reason as {@link #findWatchingTvExternalIds}: {@code WatchlistEntry}
	 * holds a bare {@code titleId} column with no association to join across in JPQL.
	 *
	 * <p>Returns raw rows, not counts. The WATCHED filter and the movie/show split live in
	 * {@code StatsService} so they are reachable by tests — this suite mocks every repository and
	 * no test executes SQL, so aggregating here would put the arithmetic somewhere nothing can
	 * check it.
	 */
	@Query(nativeQuery = true, value = """
		SELECT t.external_id AS externalId,
		       t.type AS type,
		       we.status AS status
		FROM watchlist_entries we
		JOIN titles t ON t.id = we.title_id
		WHERE we.watchlist_id = :watchlistId
		""")
	List<WatchlistTitle> findWatchlistTitles(@Param("watchlistId") Long watchlistId);
}
