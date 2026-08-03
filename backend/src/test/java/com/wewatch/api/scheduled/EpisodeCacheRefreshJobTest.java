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
	void prewarmsEveryWatchlistedShow() {
		when(titleRepository.findWatchlistedTvExternalIds()).thenReturn(List.of("95396", "1396"));

		job.refreshWatchlistedShows();

		verify(tmdbCacheService).prewarmShow("95396");
		verify(tmdbCacheService).prewarmShow("1396");
	}

	@Test
	void doesNothingWhenNobodyHasATvShowOnAWatchlist() {
		// No watchlisted shows must mean no TMDB traffic at all — an empty library should
		// not cost a nightly round-trip.
		when(titleRepository.findWatchlistedTvExternalIds()).thenReturn(List.of());

		job.refreshWatchlistedShows();

		verifyNoInteractions(tmdbCacheService);
	}

	@Test
	void doesNotRefreshShowsTheRepositoryDidNotReturn() {
		// The repository query is what scopes the job (any status, #416); the job must
		// not widen or narrow whatever it returns.
		when(titleRepository.findWatchlistedTvExternalIds()).thenReturn(List.of("95396"));

		job.refreshWatchlistedShows();

		verify(tmdbCacheService).prewarmShow("95396");
		verify(tmdbCacheService, never()).prewarmShow("1396");
	}
}
