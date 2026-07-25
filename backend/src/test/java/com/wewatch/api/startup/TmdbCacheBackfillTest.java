package com.wewatch.api.startup;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.service.TmdbCacheService;

/**
 * These backfills are the only thing that will ever populate a movie-only column on a movie cached
 * before that column existed — movie rows have no TTL refresh path, unlike TV. If a pass quietly
 * does nothing, nothing else in the suite would notice: {@code runtime_minutes} (#323) means every
 * movie on the stats page reports zero minutes, and {@code watch_providers} (#391) means Discover
 * silently under-badges the oldest movies in the library.
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

	// #394: tmdb_title_cache is now keyed by a medium-scoped cache key ("movie:550" / "tv:550"),
	// so the "not yet cached" query takes the prefix for the medium it's checking, and the two
	// movie-column queries take the "movie:" prefix's length to strip it back off.
	private static final int MOVIE_PREFIX_LENGTH = TmdbCacheKey.prefix(TitleType.MOVIE).length();

	@BeforeEach
	void setUp() {
		backfill = new TmdbCacheBackfill(titleRepository, titleCacheRepository, tmdbCacheService);
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV, TmdbCacheKey.prefix(TitleType.TV)))
			.thenReturn(List.of());
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.MOVIE, TmdbCacheKey.prefix(TitleType.MOVIE)))
			.thenReturn(List.of());
	}

	@Test
	void reprewarmsCachedMoviesThatAreMissingTheirRuntime() {
		when(titleCacheRepository.findMovieIdsMissingRuntime(MOVIE_PREFIX_LENGTH))
			.thenReturn(List.of("603", "27205"));

		backfill.backfillMissingTitles();

		// Re-prewarming rewrites the whole row, runtime included.
		verify(tmdbCacheService).prewarmMovie("603");
		verify(tmdbCacheService).prewarmMovie("27205");
	}

	@Test
	void reprewarmsCachedMoviesThatAreMissingTheirWatchProviders() {
		when(titleCacheRepository.findMovieIdsMissingRuntime(MOVIE_PREFIX_LENGTH)).thenReturn(List.of());
		when(titleCacheRepository.findMovieIdsMissingWatchProviders(MOVIE_PREFIX_LENGTH))
			.thenReturn(List.of("603", "27205"));

		backfill.backfillMissingTitles();

		// Re-prewarming rewrites the whole row, watch providers included — and with the runtime
		// query returning nothing, these two calls can only have come from the providers pass.
		verify(tmdbCacheService).prewarmMovie("603");
		verify(tmdbCacheService).prewarmMovie("27205");
		verifyNoMoreInteractions(tmdbCacheService);
	}

	@Test
	void makesNoTmdbCallsOnceEveryMovieHasARuntime() {
		// Self-limiting: a populated row drops out of the query, so the startup cost is paid once
		// rather than on every boot. The watch-providers query (#391) is deliberately left
		// unstubbed here and below — Mockito already defaults a List return to empty, and an
		// explicit stub would turn "the pass was deleted" into an UnnecessaryStubbingException in
		// these two cases, burying the one test that actually proves the pass exists.
		when(titleCacheRepository.findMovieIdsMissingRuntime(MOVIE_PREFIX_LENGTH)).thenReturn(List.of());

		backfill.backfillMissingTitles();

		verify(tmdbCacheService, never()).prewarmMovie(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void stillBackfillsTitlesWithNoCacheRowAtAll() {
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.TV, TmdbCacheKey.prefix(TitleType.TV)))
			.thenReturn(List.of("1399"));
		when(titleRepository.findExternalIdsByTypeNotInCache(TitleType.MOVIE, TmdbCacheKey.prefix(TitleType.MOVIE)))
			.thenReturn(List.of("550"));
		when(titleCacheRepository.findMovieIdsMissingRuntime(MOVIE_PREFIX_LENGTH)).thenReturn(List.of());

		backfill.backfillMissingTitles();

		verify(tmdbCacheService).prewarmShow("1399");
		verify(tmdbCacheService).prewarmMovie("550");
	}
}
