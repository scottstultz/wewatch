package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.TmdbSeasonCache;

public interface TmdbSeasonCacheRepository extends JpaRepository<TmdbSeasonCache, Long> {

	List<TmdbSeasonCache> findByTmdbId(String tmdbId);

	Optional<TmdbSeasonCache> findByTmdbIdAndSeasonNumber(String tmdbId, Integer seasonNumber);
}
