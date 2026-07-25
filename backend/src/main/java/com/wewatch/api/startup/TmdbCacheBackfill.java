package com.wewatch.api.startup;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.service.TmdbCacheService;

@Component
public class TmdbCacheBackfill {

	private static final Logger log = LoggerFactory.getLogger(TmdbCacheBackfill.class);

	private final TitleRepository titleRepository;
	private final TmdbTitleCacheRepository titleCacheRepository;
	private final TmdbCacheService tmdbCacheService;

	public TmdbCacheBackfill(
		TitleRepository titleRepository,
		TmdbTitleCacheRepository titleCacheRepository,
		TmdbCacheService tmdbCacheService
	) {
		this.titleRepository = titleRepository;
		this.titleCacheRepository = titleCacheRepository;
		this.tmdbCacheService = tmdbCacheService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void backfillMissingTitles() {
		List<String> uncachedShows = titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV);
		if (!uncachedShows.isEmpty()) {
			log.info("Backfilling TMDB episode cache for {} TV title(s) not yet cached", uncachedShows.size());
			for (String tmdbId : uncachedShows) {
				tmdbCacheService.prewarmShow(tmdbId);
			}
		}

		List<String> uncachedMovies = titleRepository.findExternalIdsByTypeNotInCache(TitleType.MOVIE);
		if (!uncachedMovies.isEmpty()) {
			log.info("Backfilling TMDB metadata cache for {} movie(s) not yet cached", uncachedMovies.size());
			for (String tmdbId : uncachedMovies) {
				tmdbCacheService.prewarmMovie(tmdbId);
			}
		}

		// Movies cached before #323 have no runtime, and nothing else would ever fetch it:
		// unlike TV, a movie cache row is never TTL-refreshed. Without this pass the stats
		// page would report a watch time of zero minutes for every movie in the library.
		// Re-prewarming rewrites the whole row, runtime included.
		List<String> moviesMissingRuntime = titleCacheRepository.findMovieIdsMissingRuntime();
		if (!moviesMissingRuntime.isEmpty()) {
			log.info("Backfilling runtime for {} cached movie(s) missing it", moviesMissingRuntime.size());
			for (String tmdbId : moviesMissingRuntime) {
				tmdbCacheService.prewarmMovie(tmdbId);
			}
		}

		// Movies cached before #270 have no watch providers, and — exactly as with runtime above —
		// nothing else would ever fetch them: a movie cache row is never TTL-refreshed. The
		// suggestion pipeline's badges already read this column (ProviderContextResolver
		// .attachBadges), so a stale row silently under-badges older movies (#391).
		// Re-prewarming rewrites the whole row, providers included.
		List<String> moviesMissingProviders = titleCacheRepository.findMovieIdsMissingWatchProviders();
		if (!moviesMissingProviders.isEmpty()) {
			log.info("Backfilling watch providers for {} cached movie(s) missing them", moviesMissingProviders.size());
			for (String tmdbId : moviesMissingProviders) {
				tmdbCacheService.prewarmMovie(tmdbId);
			}
		}
	}
}
