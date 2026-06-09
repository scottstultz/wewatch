package com.wewatch.api.startup;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.service.TmdbCacheService;

@Component
public class TmdbCacheBackfill {

	private static final Logger log = LoggerFactory.getLogger(TmdbCacheBackfill.class);

	private final TitleRepository titleRepository;
	private final TmdbCacheService tmdbCacheService;

	public TmdbCacheBackfill(TitleRepository titleRepository, TmdbCacheService tmdbCacheService) {
		this.titleRepository = titleRepository;
		this.tmdbCacheService = tmdbCacheService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void backfillMissingShows() {
		List<String> uncachedIds = titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV);
		if (uncachedIds.isEmpty()) {
			return;
		}
		log.info("Backfilling TMDB episode cache for {} TV title(s) not yet cached", uncachedIds.size());
		for (String tmdbId : uncachedIds) {
			tmdbCacheService.prewarmShow(tmdbId);
		}
	}
}
