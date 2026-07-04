package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.tmdb.TmdbClient;

@ExtendWith(MockitoExtension.class)
class SuggestionServiceTest {

	@Mock private WatchlistEntryRepository watchlistEntryRepository;
	@Mock private WatchlistMemberRepository watchlistMemberRepository;
	@Mock private TitleService titleService;
	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository tmdbTitleCacheRepository;
	@Mock private SuggestionImpressionService suggestionImpressionService;

	private static final long WATCHLIST_ID = 42L;
	// The watchlist's members — suppression is scoped to these users, not the list (#247)
	private static final List<Long> MEMBER_IDS = List.of(7L, 8L);
	private static final Instant DAY_1 = Instant.parse("2026-07-03T12:00:00Z");
	private static final Instant DAY_2 = Instant.parse("2026-07-04T12:00:00Z");

	private SuggestionService serviceAt(Instant now) {
		return new SuggestionService(
			watchlistEntryRepository, watchlistMemberRepository, titleService, tmdbClient,
			tmdbTitleCacheRepository, suggestionImpressionService,
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
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
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
	void watchedTitlesContributeToGenreProfileScoring() {
		// One WATCHING seed with no cached metadata, one WATCHED entry whose cached
		// genre (99) is the only source for the taste profile
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of("ext2", cacheRow("ext2", List.of(99), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(new TitleSearchResponse("rec-hit", "TMDB", TitleType.TV, "Hit",
			null, null, null, List.of(99)));
		IntStream.rangeClosed(1, 11).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		// Only the WATCHING entry seeds a shelf, but the WATCHED entry's genre
		// ranks the matching candidate first
		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).reason()).isEqualTo("Because you added Show 1");
		assertThat(shelves.get(0).titles().get(0).externalId()).isEqualTo("rec-hit");
	}

	@Test
	void watchedTitlesSeedFinishedShelvesNotPerSeedShelves() {
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> entry(i, "ext" + i, WatchStatus.WATCHED))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		// WATCHED entries never get "Because you added X" shelves (#232); exactly
		// one of them seeds a "Because you finished X" shelf instead (#235)
		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.FINISHED_SEED);
		assertThat(shelves.get(0).reason()).startsWith("Because you finished Show ");
		verify(tmdbClient, times(1)).getRecommendations(any(), anyString(), anyInt());
	}

	@Test
	void finishedSeedComesFromTheMostRecentFinishes() {
		// Ten WATCHED entries finished a day apart: the seed pool is the five
		// most recently updated, so the shelf's seed must be one of ids 6–10
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 10)
			.mapToObj(i -> {
				WatchlistEntry e = entry(i, "ext" + i, WatchStatus.WATCHED);
				e.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i * 86400L));
				return e;
			})
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 10)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).reason()).matches("Because you finished Show (6|7|8|9|10)");
	}

	@Test
	void keywordMatchesRankAboveGenreOnlyMatches() {
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		// The owned title carries genre 10 and keyword 7; one candidate has a cache
		// row sharing keyword 7
		stubCacheRows(Map.of(
			"ext1", cacheRow("ext1", List.of(10), List.of(7)),
			"rec-keyword", cacheRow("rec-keyword", List.of(10), List.of(7))));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(new TitleSearchResponse("rec-genre", "TMDB", TitleType.TV, "Genre only",
			null, null, null, List.of(10)));
		candidates.add(new TitleSearchResponse("rec-keyword", "TMDB", TitleType.TV, "Keyword match",
			null, null, null, List.of(10)));
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		// Both share genre 10 with the profile; the shared keyword breaks the tie
		assertThat(shelves).hasSize(1);
		List<TitleSearchResponse> shelf = shelves.get(0).titles();
		assertThat(shelf.get(0).externalId()).isEqualTo("rec-keyword");
		assertThat(shelf.get(1).externalId()).isEqualTo("rec-genre");
	}

	@Test
	void recentlyShownTitlesAreExcludedFromShelves() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// Suppress one candidate per potential seed; plenty of fresh ones remain
		Set<String> suppressed = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> "rec-ext" + i + "-1")
			.collect(Collectors.toSet());
		when(suggestionImpressionService.recentlyShownIds(MEMBER_IDS)).thenReturn(suppressed);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).isNotEmpty();
		assertThat(shelves).allSatisfy(shelf ->
			assertThat(shelf.titles()).noneMatch(t -> suppressed.contains(t.externalId())));
	}

	@Test
	void suppressionRelaxesToKeepTheShelfAtMinimumSize() {
		// One seed with five candidates, three of them recently shown: only two are
		// fresh, so one suppressed title must come back to reach MIN_SHELF_SIZE
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 5).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		Set<String> suppressed = Set.of("rec-1", "rec-2", "rec-3");
		when(suggestionImpressionService.recentlyShownIds(MEMBER_IDS)).thenReturn(suppressed);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		List<String> shelfIds = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		// Both fresh candidates plus exactly one suppressed top-up
		assertThat(shelfIds).hasSize(3);
		assertThat(shelfIds).contains("rec-4", "rec-5");
		assertThat(shelfIds.stream().filter(suppressed::contains)).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void topUpTitlesAreNotReRecordedAsImpressions() {
		// Same setup as the top-up test: two fresh candidates and one suppressed title
		// pulled back in to reach MIN_SHELF_SIZE. The topped-up title must NOT be
		// re-recorded, or its suppression clock resets and it never ages out (#246).
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 5).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		Set<String> suppressed = Set.of("rec-1", "rec-2", "rec-3");
		when(suggestionImpressionService.recentlyShownIds(MEMBER_IDS)).thenReturn(suppressed);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		String toppedUpId = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId)
			.filter(suppressed::contains)
			.findFirst().orElseThrow();

		ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
		verify(suggestionImpressionService).recordShown(eq(MEMBER_IDS), captor.capture());
		Set<String> recorded = captor.getValue();
		// Fresh titles are recorded; the topped-up (previously suppressed) one is not
		assertThat(recorded).contains("rec-4", "rec-5");
		assertThat(recorded).doesNotContain(toppedUpId);
	}

	@Test
	void computedShelvesAreRecordedAsImpressions() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		Set<String> shownIds = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.collect(Collectors.toSet());
		assertThat(shownIds).isNotEmpty();
		verify(suggestionImpressionService).recordShown(MEMBER_IDS, shownIds);
	}

	@Test
	void emptyWatchlistRecordsNoImpressions() {
		stubEmptyWatchlist();

		assertThat(serviceAt(DAY_1).topPicks(WATCHLIST_ID)).isEmpty();

		verify(suggestionImpressionService, never()).recordShown(any(), any());
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

	@Test
	void newReleasesShelfUsesReleaseWindowAndLowerVoteFloor() {
		stubPopulatedWatchlistWithGenres();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// Only the new-release discover variant yields candidates, so whatever
		// order the day's rotation tries the exploration kinds, this shelf fills.
		// lenient: other discover variants hit this method with non-matching args
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(20), eq("popularity.desc"), notNull(), notNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("nr-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.NEW_RELEASES);
			assertThat(shelf.reason()).isEqualTo("New in your genres");
		});
		// The window is the 60 days ending "today" on the fixed clock
		verify(tmdbClient, atLeastOnce()).discover(eq(TitleType.TV), eq(List.of(99)), eq(List.of()), eq(20),
			eq("popularity.desc"), eq(LocalDate.of(2026, 5, 4)), eq(LocalDate.of(2026, 7, 3)), anyInt());
	}

	@Test
	void hiddenGemsShelfSortsByRatingWithModerateVoteFloor() {
		stubPopulatedWatchlistWithGenres();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// lenient: other discover variants hit this method with non-matching args
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(200), eq("vote_average.desc"), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gem-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
			assertThat(shelf.reason()).isEqualTo("Hidden gems");
		});
	}

	@Test
	void trendingShelfRanksByGenreProfileAffinity() {
		// The owned titles' cached genre 99 builds the taste profile; per-seed
		// and discover sources return nothing, so only trending can fill
		stubPopulatedWatchlistWithGenres();
		List<TitleSearchResponse> trending = new ArrayList<>();
		trending.add(new TitleSearchResponse("trend-hit", "TMDB", TitleType.TV, "Hit",
			null, null, null, List.of(99)));
		IntStream.rangeClosed(1, 11).forEach(i -> trending.add(candidate("trend-" + i)));
		when(tmdbClient.getTrending(eq(TitleType.TV), anyInt())).thenReturn(trending);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.TRENDING);
			assertThat(shelf.reason()).isEqualTo("Trending now");
			assertThat(shelf.titles().get(0).externalId()).isEqualTo("trend-hit");
		});
	}

	@Test
	void explorationShelvesAreCappedAndOrderedAfterSimilarityShelves() {
		stubPopulatedWatchlistWithGenres();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// Every exploration source is rich: whichever two kinds the day's
		// rotation tries first fill, and the third is never fetched
		AtomicInteger batch = new AtomicInteger();
		when(tmdbClient.discover(any(), any(), any(), anyInt(), anyString(), any(), any(), anyInt()))
			.thenAnswer(inv -> {
				int b = batch.incrementAndGet();
				return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("d" + b + "-" + i)).toList();
			});
		lenient().when(tmdbClient.getTrending(any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("t-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		Set<SuggestionShelfResponse.ShelfKind> exploration = Set.of(
			SuggestionShelfResponse.ShelfKind.NEW_RELEASES,
			SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS,
			SuggestionShelfResponse.ShelfKind.TRENDING);
		assertThat(shelves.stream().filter(s -> exploration.contains(s.kind()))).hasSize(2);
		// Exploration shelves sit at the end, after every similarity shelf
		int firstExploration = IntStream.range(0, shelves.size())
			.filter(i -> exploration.contains(shelves.get(i).kind()))
			.findFirst().orElseThrow();
		assertThat(shelves.subList(firstExploration, shelves.size()))
			.allSatisfy(s -> assertThat(exploration).contains(s.kind()));
	}

	// Like stubPopulatedWatchlist, but every owned title carries cached genre 99,
	// enabling genre-profile and exploration shelves
	private void stubPopulatedWatchlistWithGenres() {
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> entry(i, "ext" + i))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));

		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		stubCacheRows(IntStream.rangeClosed(1, 5).boxed()
			.collect(Collectors.toMap(i -> "ext" + i, i -> cacheRow("ext" + i, List.of(99), null))));
	}

	private WatchlistEntry entry(long id, String externalId) {
		return entry(id, externalId, WatchStatus.WATCHING);
	}

	private WatchlistEntry entry(long id, String externalId, WatchStatus status) {
		WatchlistEntry e = new WatchlistEntry();
		e.setId(id);
		e.setWatchlistId(WATCHLIST_ID);
		e.setTitleId(id);
		e.setExternalId(externalId);
		e.setExternalSource("TMDB");
		e.setStatus(status);
		e.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
		return e;
	}

	private TmdbTitleCache cacheRow(String tmdbId, List<Integer> genreIds, List<Integer> keywordIds) {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(tmdbId);
		c.setGenreIds(genreIds);
		c.setKeywordIds(keywordIds);
		return c;
	}

	// Answer-based stub: findAllById is called once for owned ids and once per seed
	// for candidate ids, so return whichever of the given rows were requested
	private void stubCacheRows(Map<String, TmdbTitleCache> rows) {
		when(tmdbTitleCacheRepository.findAllById(any())).thenAnswer(inv -> {
			Iterable<String> ids = inv.getArgument(0);
			List<TmdbTitleCache> out = new ArrayList<>();
			for (String id : ids) {
				if (rows.containsKey(id)) out.add(rows.get(id));
			}
			return out;
		});
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
