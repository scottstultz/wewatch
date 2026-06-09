package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.TmdbEpisodeCache;

public interface TmdbEpisodeCacheRepository extends JpaRepository<TmdbEpisodeCache, Long> {

	List<TmdbEpisodeCache> findByTmdbIdAndSeasonNumber(String tmdbId, Integer seasonNumber);

	Optional<TmdbEpisodeCache> findByTmdbIdAndSeasonNumberAndEpisodeNumber(
		String tmdbId, Integer seasonNumber, Integer episodeNumber);
}
