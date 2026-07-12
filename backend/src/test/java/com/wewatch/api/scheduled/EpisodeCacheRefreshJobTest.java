package com.wewatch.api.scheduled;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.service.TmdbCacheService;

/**
 * Drives the job method directly — {@code @Scheduled} does not fire outside a running
 * application context, so the cron expression itself is verified by hand (see
 * docs/architecture.md).
 */
@ExtendWith(MockitoExtension.class)
class EpisodeCacheRefreshJobTest {

	@Mock
	private TitleRepository titleRepository;

	@Mock
	private TmdbCacheService tmdbCacheService;

	@InjectMocks
	private EpisodeCacheRefreshJob job;

	@Test
	void prewarmsEveryShowSomeoneIsWatching() {
		when(titleRepository.findWatchingTvExternalIds()).thenReturn(List.of("95396", "1396"));

		job.refreshWatchingShows();

		verify(tmdbCacheService).prewarmShow("95396");
		verify(tmdbCacheService).prewarmShow("1396");
	}

	@Test
	void doesNothingWhenNobodyIsWatchingATvShow() {
		// No WATCHING shows must mean no TMDB traffic at all — an empty library should
		// not cost a nightly round-trip.
		when(titleRepository.findWatchingTvExternalIds()).thenReturn(List.of());

		job.refreshWatchingShows();

		verifyNoInteractions(tmdbCacheService);
	}

	@Test
	void doesNotRefreshShowsNobodyIsWatching() {
		// The repository query is what scopes the job; the job must not widen it.
		when(titleRepository.findWatchingTvExternalIds()).thenReturn(List.of("95396"));

		job.refreshWatchingShows();

		verify(tmdbCacheService).prewarmShow("95396");
		verify(tmdbCacheService, never()).prewarmShow("1396");
	}
}
