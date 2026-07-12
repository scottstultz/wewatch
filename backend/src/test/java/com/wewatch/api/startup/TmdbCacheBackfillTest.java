package com.wewatch.api.startup;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.service.TmdbCacheService;

/**
 * The runtime backfill (#323) is the only thing that will ever populate
 * {@code tmdb_title_cache.runtime_minutes} on a movie cached before the column existed — movie
 * rows have no TTL refresh path, unlike TV. If this pass quietly does nothing, every movie on the
 * stats page reports zero minutes, and nothing else in the suite would notice.
 */
@ExtendWith(MockitoExtension.class)
class TmdbCacheBackfillTest {

	@Mock
	private TitleRepository titleRepository;

	@Mock
	private TmdbTitleCacheRepository titleCacheRepository;

	@Mock
	private TmdbCacheService tmdbCacheService;

	private TmdbCacheBackfill backfill;

	@BeforeEach
	void setUp() {
		backfill = new TmdbCacheBackfill(titleRepository, titleCacheRepository, tmdbCacheService);
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV)).thenReturn(List.of());
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.MOVIE)).thenReturn(List.of());
	}

	@Test
	void reprewarmsCachedMoviesThatAreMissingTheirRuntime() {
		when(titleCacheRepository.findMovieIdsMissingRuntime()).thenReturn(List.of("603", "27205"));

		backfill.backfillMissingTitles();

		// Re-prewarming rewrites the whole row, runtime included.
		verify(tmdbCacheService).prewarmMovie("603");
		verify(tmdbCacheService).prewarmMovie("27205");
	}

	@Test
	void makesNoTmdbCallsOnceEveryMovieHasARuntime() {
		// Self-limiting: a populated row drops out of the query, so the startup cost is paid once
		// rather than on every boot.
		when(titleCacheRepository.findMovieIdsMissingRuntime()).thenReturn(List.of());

		backfill.backfillMissingTitles();

		verify(tmdbCacheService, never()).prewarmMovie(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void stillBackfillsTitlesWithNoCacheRowAtAll() {
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV)).thenReturn(List.of("1399"));
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.MOVIE)).thenReturn(List.of("550"));
		when(titleCacheRepository.findMovieIdsMissingRuntime()).thenReturn(List.of());

		backfill.backfillMissingTitles();

		verify(tmdbCacheService).prewarmShow("1399");
		verify(tmdbCacheService).prewarmMovie("550");
	}
}
