package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.tmdb.TmdbClient;

@ExtendWith(MockitoExtension.class)
class SuggestionServiceTest {

	@Mock private WatchlistEntryRepository watchlistEntryRepository;
	@Mock private TitleService titleService;
	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository tmdbTitleCacheRepository;

	private SuggestionService service;

	private static final long WATCHLIST_ID = 42L;

	@BeforeEach
	void setUp() {
		service = new SuggestionService(
			watchlistEntryRepository, titleService, tmdbClient, tmdbTitleCacheRepository, 30L, 1000L);
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(Page.empty());
	}

	@Test
	void topPicksServesSecondCallFromCache() {
		service.topPicks(WATCHLIST_ID);
		service.topPicks(WATCHLIST_ID);

		verify(watchlistEntryRepository, times(1))
			.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class));
	}

	@Test
	void recomputeRefreshesCacheWithoutInvalidatingReads() {
		service.topPicks(WATCHLIST_ID);
		service.recompute(WATCHLIST_ID);
		assertThat(service.topPicks(WATCHLIST_ID)).isEmpty();

		// One compute from the initial miss, one from recompute; the final read is cached
		verify(watchlistEntryRepository, times(2))
			.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class));
	}
}
