package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.tmdb.TmdbClient;

@ExtendWith(MockitoExtension.class)
class SuggestionServiceTest {

	@Mock private WatchlistEntryRepository watchlistEntryRepository;
	@Mock private TitleService titleService;
	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository tmdbTitleCacheRepository;

	private static final long WATCHLIST_ID = 42L;
	private static final Instant DAY_1 = Instant.parse("2026-07-03T12:00:00Z");
	private static final Instant DAY_2 = Instant.parse("2026-07-04T12:00:00Z");

	private SuggestionService serviceAt(Instant now) {
		return new SuggestionService(
			watchlistEntryRepository, titleService, tmdbClient, tmdbTitleCacheRepository,
			Clock.fixed(now, ZoneOffset.UTC), 30L, 1000L);
	}

	private void stubEmptyWatchlist() {
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(Page.empty());
	}

	// Five WATCHING entries with cached titles but no TMDB cache rows, so per-seed
	// shelves are built while genre-profile (discover) shelves are skipped
	private void stubPopulatedWatchlist() {
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> entry(i, "ext" + i))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));

		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
	}

	// Namespaced per seed title so cross-shelf dedup doesn't empty later shelves
	private List<TitleSearchResponse> candidatesFor(String tmdbId) {
		return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("rec-" + tmdbId + "-" + i)).toList();
	}

	@Test
	void topPicksServesSecondCallFromCache() {
		stubEmptyWatchlist();
		SuggestionService service = serviceAt(DAY_1);

		service.topPicks(WATCHLIST_ID);
		service.topPicks(WATCHLIST_ID);

		verify(watchlistEntryRepository, times(1))
			.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class));
	}

	@Test
	void recomputeRefreshesCacheWithoutInvalidatingReads() {
		stubEmptyWatchlist();
		SuggestionService service = serviceAt(DAY_1);

		service.topPicks(WATCHLIST_ID);
		service.recompute(WATCHLIST_ID);
		assertThat(service.topPicks(WATCHLIST_ID)).isEmpty();

		// One compute from the initial miss, one from recompute; the final read is cached
		verify(watchlistEntryRepository, times(2))
			.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class));
	}

	@Test
	void shelvesAreIdenticalAcrossRecomputesOnTheSameDay() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> first = serviceAt(DAY_1).topPicks(WATCHLIST_ID);
		List<SuggestionShelfResponse> second = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(first).isNotEmpty();
		assertThat(second).isEqualTo(first);
	}

	@Test
	void shelvesDifferAcrossDaysForAnUnchangedWatchlist() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> day1 = serviceAt(DAY_1).topPicks(WATCHLIST_ID);
		List<SuggestionShelfResponse> day2 = serviceAt(DAY_2).topPicks(WATCHLIST_ID);

		assertThat(day1).isNotEmpty();
		assertThat(day2).isNotEqualTo(day1);
	}

	@Test
	void recommendationPagesStayWithinTheRotationRange() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		verify(tmdbClient, times(3)).getRecommendations(any(), anyString(), intThat(p -> p >= 1 && p <= 3));
		verify(tmdbClient, never()).getRecommendations(any(), anyString(), intThat(p -> p < 1 || p > 3));
	}

	@Test
	void emptyDeeperPageFallsBackToPageOne() {
		stubPopulatedWatchlist();
		// Deeper pages are empty; only page 1 has results
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> (int) inv.getArgument(2) == 1 ? candidatesFor(inv.getArgument(1)) : List.of());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		// A page > 1 was drawn and came back empty, yet every shelf was still built from page 1
		verify(tmdbClient, atLeastOnce()).getRecommendations(any(), anyString(), intThat(p -> p > 1));
		assertThat(shelves).hasSize(3);
		assertThat(shelves).allSatisfy(shelf ->
			assertThat(shelf.titles()).isNotEmpty());
	}

	private WatchlistEntry entry(long id, String externalId) {
		WatchlistEntry e = new WatchlistEntry();
		e.setId(id);
		e.setWatchlistId(WATCHLIST_ID);
		e.setTitleId(id);
		e.setExternalId(externalId);
		e.setExternalSource("TMDB");
		e.setStatus(WatchStatus.WATCHING);
		e.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
		return e;
	}

	private Title title(long id, String externalId) {
		Title t = new Title();
		t.setId(id);
		t.setExternalId(externalId);
		t.setExternalSource("TMDB");
		t.setType(TitleType.TV);
		t.setName("Show " + id);
		return t;
	}

	private TitleSearchResponse candidate(String externalId) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.TV, "Title " + externalId,
			null, null, null, List.of());
	}
}
