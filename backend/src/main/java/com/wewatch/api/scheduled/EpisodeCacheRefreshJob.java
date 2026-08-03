package com.wewatch.api.scheduled;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.service.TmdbCacheService;

/**
 * Keeps the episode cache current for every TV show on a watchlist (#321), regardless of
 * status — Watching, Watched, and Want to Watch all qualify for "Returning this week" (#416),
 * since a caught-up show parked in either of the latter two is exactly what that panel exists
 * to resurface.
 *
 * <p>Without this the cache is only ever written when a title is added, backfilled, or
 * when someone opens a season — so a season announced after the last prewarm is simply
 * absent, and "Returning this week" would confidently report nothing. The refresh is what
 * makes that surface trustworthy rather than decorative.
 *
 * <p>Scoped to whatever TV shows are on a watchlist, not the whole TMDB catalog, and costs
 * {@code 1 + seasons} TMDB calls per show once a night. Note this is no longer bounded to
 * "tens of titles" the way the WATCHING-only scope was — call volume now tracks the size of
 * the TV library across every status. Set {@code app.episode-cache.refresh-cron=-} to disable.
 */
@Component
public class EpisodeCacheRefreshJob {

	private static final Logger log = LoggerFactory.getLogger(EpisodeCacheRefreshJob.class);

	private final TitleRepository titleRepository;
	private final TmdbCacheService tmdbCacheService;

	public EpisodeCacheRefreshJob(TitleRepository titleRepository, TmdbCacheService tmdbCacheService) {
		this.titleRepository = titleRepository;
		this.tmdbCacheService = tmdbCacheService;
	}

	@Scheduled(cron = "${app.episode-cache.refresh-cron:0 30 3 * * *}")
	public void refreshWatchlistedShows() {
		List<String> tmdbIds = titleRepository.findWatchlistedTvExternalIds();
		if (tmdbIds.isEmpty()) {
			return;
		}
		log.info("Refreshing TMDB episode cache for {} watchlisted show(s)", tmdbIds.size());
		for (String tmdbId : tmdbIds) {
			// prewarmShow is @Async and swallows its own failures: one unreachable show
			// must not cost the rest of the refresh.
			tmdbCacheService.prewarmShow(tmdbId);
		}
	}
}
