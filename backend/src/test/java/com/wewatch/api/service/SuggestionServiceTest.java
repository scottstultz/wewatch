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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
	void rankingRotatesAcrossDaysWhenCandidateScoresAreUnequal() {
		// One WATCHING seed (ext1, genre 10 → weight 2) and one WANT_TO_WATCH title
		// (ext2, genre 11 → weight 1) build a graded genre profile. The seed's
		// candidates carry distinct affinities (0/1/2/3) whose gaps fall within the
		// ±1.0 score jitter (#248) band, so day-to-day ordering must change even
		// though the underlying scores are unequal — not just among tied candidates.
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WANT_TO_WATCH));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of(
			"ext1", cacheRow("ext1", List.of(10), null),
			"ext2", cacheRow("ext2", List.of(11), null)));

		// Distinct filler primary genres keep the per-genre diversification cap out
		// of the picture; only genres 10/11 contribute to the score
		List<TitleSearchResponse> candidates = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			candidates.add(scored("hit-both-" + i, List.of(3000 + i, 10, 11))); // score 3
			candidates.add(scored("hit-ten-" + i, List.of(1000 + i, 10)));      // score 2
			candidates.add(scored("hit-eleven-" + i, List.of(2000 + i, 11)));   // score 1
			candidates.add(scored("miss-" + i, List.of(4000 + i)));             // score 0
		}
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<String> day1 = seededShelfOrder(serviceAt(DAY_1).topPicks(WATCHLIST_ID));
		List<String> day2 = seededShelfOrder(serviceAt(DAY_2).topPicks(WATCHLIST_ID));

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
		// genres (97/98/99) are the only source for the taste profile. The gap is
		// several genres wide so it clears the ±1.0 score jitter (#248) and the hit
		// still ranks first deterministically.
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of("ext2", cacheRow("ext2", List.of(97, 98, 99), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(new TitleSearchResponse("rec-hit", "TMDB", TitleType.TV, "Hit",
			null, null, null, List.of(97, 98, 99)));
		IntStream.rangeClosed(1, 11).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		// Only the WATCHING entry seeds a shelf, but the WATCHED entry's genres
		// rank the matching candidate first
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
	void recentlyShownTitlesSinkToTheBottomOfAFullShelf() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// One shown-yesterday candidate per potential seed: full recency weight
		// demotes it past every fresh candidate in its 12-deep pool (#264)
		Set<String> shown = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> "rec-ext" + i + "-1")
			.collect(Collectors.toSet());
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(shown.stream().collect(Collectors.toMap(Function.identity(), id -> 1.0)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).isNotEmpty();
		assertThat(shelves).allSatisfy(shelf -> {
			List<String> ids = shelf.titles().stream().map(TitleSearchResponse::externalId).toList();
			// The shelf still fills completely — the shown title sinks, it doesn't vanish
			assertThat(ids).hasSize(12);
			assertThat(shown).contains(ids.get(11));
			assertThat(ids.subList(0, 11)).noneMatch(shown::contains);
		});
	}

	@Test
	void thinPoolsFillPastTheOldSuppressionFloor() {
		// One seed with five candidates, three of them recently shown: under binary
		// suppression this shelf pinned at MIN_SHELF_SIZE; under the soft penalty
		// (#264) it serves the whole pool, fresh candidates first
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 5).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(Map.of("rec-1", 1.0, "rec-2", 1.0, "rec-3", 1.0));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		List<String> shelfIds = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		assertThat(shelfIds).hasSize(5);
		assertThat(shelfIds.subList(0, 2)).containsExactlyInAnyOrder("rec-4", "rec-5");
		assertThat(shelfIds.subList(2, 5)).containsExactlyInAnyOrder("rec-1", "rec-2", "rec-3");
	}

	@Test
	void recencyPenaltyDecaysFromYesterdayToTheWindowEdge() {
		// A graded profile (genres 10–13, weight 2 each) pins the base ranking beyond
		// the ±1.0 jitter: shown-yesterday (affinity 8) ranks first, shown-week-ago
		// (affinity 4) second, zero-affinity fillers after. The penalty then sinks
		// yesterday's title below every fresh candidate despite its top affinity,
		// while the window-edge title only slips a couple of positions (#264).
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", List.of(10, 11, 12, 13), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(scored("shown-yesterday", List.of(10, 11, 12, 13)));
		candidates.add(scored("shown-week-ago", List.of(10, 11)));
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(Map.of("shown-yesterday", 1.0, "shown-week-ago", 1.0 / 7));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		List<String> ids = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		assertThat(ids).hasSize(12);
		assertThat(ids.get(11)).isEqualTo("shown-yesterday");
		// Demoted 16/7 ≈ 2.3 positions from the top: rank mostly recovered
		assertThat(ids.indexOf("shown-week-ago")).isEqualTo(2);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reServedTitlesAreRecordedAgain() {
		// Thin pool: three previously shown titles are re-served alongside the fresh
		// ones. Under the soft penalty every served title is re-recorded (#264) —
		// re-serving just starts the sink again, unlike the binary window where a
		// re-record trapped top-ups forever (#246).
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 5).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(Map.of("rec-1", 1.0, "rec-2", 1.0, "rec-3", 1.0));

		serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
		verify(suggestionImpressionService).recordShown(eq(MEMBER_IDS), captor.capture());
		assertThat(captor.getValue())
			.containsExactlyInAnyOrder("rec-1", "rec-2", "rec-3", "rec-4", "rec-5");
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

	@Test
	void genreProfileDiscoverDrawsFromDeeperRange() {
		// Genre-profile discover is the vote-floor-100 popularity.desc variant with no
		// release window (#249: deepened from a 3-page to a 6-page draw range)
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gp-" + i)).toList());

		for (int d = 0; d < 40; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).discover(any(), any(), any(), eq(100), eq("popularity.desc"),
			isNull(), isNull(), page.capture());
		assertThat(page.getAllValues()).allSatisfy(p -> assertThat(p).isBetween(1, 6));
		// Across days the draw reaches past the old 3-page ceiling
		assertThat(page.getAllValues()).anyMatch(p -> p > 3);
	}

	@Test
	void hiddenGemsDrawsFromDeepBandSkippingTheStaticHead() {
		// Only the hidden-gems discover variant fills, so the exploration rotation
		// always reaches it and exercises its page draw every day (#249)
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(200), eq("vote_average.desc"), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gem-" + i)).toList());

		for (int d = 0; d < 40; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).discover(any(), any(), any(), eq(200), eq("vote_average.desc"),
			isNull(), isNull(), page.capture());
		// Every draw lands in the mid-deep band [4, 18] — never the static top-rated
		// head (pages 1–3) that is identical for everyone with the same genre profile
		assertThat(page.getAllValues()).allSatisfy(p -> assertThat(p).isBetween(4, 18));
		assertThat(page.getAllValues()).doesNotContain(1, 2, 3);
		// And the page actually rotates across days rather than pinning to one deep page
		assertThat(new HashSet<>(page.getAllValues())).hasSizeGreaterThan(1);
	}

	@Test
	void sameGenreDiscoverPageFillsTheShelfWithoutGenreCapping() {
		// Genre-profile discover is filtered to the user's top genres by construction,
		// so a full page shares one genre; exempt from the cluster cap (#265), the
		// shelf fills to MAX_SHELF_SIZE instead of being chopped to ~4
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("gp-" + i, List.of(99))).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.GENRE_PROFILE);
		assertThat(shelves.get(0).titles()).hasSize(12);
	}

	@Test
	void discoverExplorationShelvesSkipTheCapButTrendingKeepsIt() {
		// Hidden gems is discover-backed (genre-filtered → exempt, #265) while
		// trending carries a real genre mix and stays diversified
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(200), eq("vote_average.desc"), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("gem-" + i, List.of(99))).toList());
		lenient().when(tmdbClient.getTrending(any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("t-" + i, List.of(99))).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
			assertThat(shelf.titles()).hasSize(12);
		});
		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.TRENDING);
			assertThat(shelf.titles()).hasSize(4);
		});
	}

	@Test
	void recommendationShelvesStillCapSameGenreRuns() {
		// Mixed recommendation feeds keep diversification (#265): a run of 20
		// candidates sharing their only genre is capped at MAX_PER_GENRE_CLUSTER
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 20).mapToObj(i -> scored("rec-" + i, List.of(50))).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.PER_SEED);
		assertThat(shelves.get(0).titles()).hasSize(4);
	}

	@Test
	void anUnderCapSecondaryGenreAdmitsACandidatePastThePrimaryGenreCap() {
		// The cap keys on the candidate's full genre set, not TMDB's arbitrary first
		// genre id (#265): once genres 10/11/50 saturate on the high-affinity pure
		// candidates, the low-affinity dual still enters through its fresh genre 60
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", List.of(10, 11), null)));

		// Pures score 4 (genres 10+11, weight 2 each), the dual scores 0 — a gap
		// beyond the ±1.0 jitter, so exactly four pures rank ahead of the dual
		List<TitleSearchResponse> candidates = new ArrayList<>();
		IntStream.rangeClosed(1, 8).forEach(i -> candidates.add(scored("pure-" + i, List.of(10, 11, 50))));
		candidates.add(scored("dual", List.of(50, 60)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		List<String> ids = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		// Four pures fill the 10/11/50 clusters; the dual's genre 50 is saturated
		// but genre 60 is not, so it gets the fifth slot instead of being skipped
		assertThat(ids).hasSize(5);
		assertThat(ids.get(4)).isEqualTo("dual");
	}

	@Test
	void trendingPagesStayShallow() {
		// Trending/week thins fast, so its draw stays in the shallow 1–3 range (#249)
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.getTrending(any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("t-" + i)).toList());

		for (int d = 0; d < 30; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, atLeastOnce()).getTrending(any(), intThat(p -> p >= 1 && p <= 3));
		verify(tmdbClient, never()).getTrending(any(), intThat(p -> p < 1 || p > 3));
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

	private TitleSearchResponse scored(String externalId, List<Integer> genreIds) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.TV, "Title " + externalId,
			null, null, null, genreIds);
	}

	// externalId order of the single per-seed shelf, for comparing day-to-day ranking
	private List<String> seededShelfOrder(List<SuggestionShelfResponse> shelves) {
		return shelves.stream()
			.filter(s -> s.kind() == SuggestionShelfResponse.ShelfKind.PER_SEED)
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.toList();
	}
}
