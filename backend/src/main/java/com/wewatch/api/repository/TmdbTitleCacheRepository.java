package com.wewatch.api.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.TmdbTitleCache;

public interface TmdbTitleCacheRepository extends JpaRepository<TmdbTitleCache, String> {

	Optional<TmdbTitleCache> findByTmdbId(String tmdbId);
}
