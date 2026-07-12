package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.wewatch.api.model.TmdbTitleCache;

public interface TmdbTitleCacheRepository extends JpaRepository<TmdbTitleCache, String> {

	Optional<TmdbTitleCache> findByTmdbId(String tmdbId);

	/**
	 * Movies cached before #323, whose runtime was never persisted — the backfill set for
	 * {@code TmdbCacheBackfill}.
	 *
	 * <p>Movie cache rows have no TTL refresh path (only {@code getSeasons}/{@code
	 * getSeasonDetail} re-fetch, and those are TV-only), so without an explicit pass this
	 * column would stay null forever on every row that predates the migration. Self-limiting:
	 * once a row is re-prewarmed it drops out of this result, so the startup cost is paid once.
	 *
	 * <p>Note this also re-selects movies TMDB genuinely has no runtime for, which stay null and
	 * so are retried on each boot. That is a handful of obscure titles, one async call each.
	 */
	@Query("SELECT c.tmdbId FROM TmdbTitleCache c WHERE c.type = 'MOVIE' AND c.runtimeMinutes IS NULL")
	List<String> findMovieIdsMissingRuntime();
}
