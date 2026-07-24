package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.HashMap;
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

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.CachedKeyword;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.tmdb.TmdbClient;

@ExtendWith(MockitoExtension.class)
class SuggestionServiceTest {

	@Mock private WatchlistEntryRepository watchlistEntryRepository;
	@Mock private WatchlistMemberRepository watchlistMemberRepository;
	@Mock private UserRepository userRepository;
	@Mock private TitleService titleService;
	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository tmdbTitleCacheRepository;
	@Mock private SuggestionImpressionService suggestionImpressionService;
	@Mock private SuggestionDismissalService suggestionDismissalService;
	@Mock private TitleRatingService titleRatingService;

	private static final long WATCHLIST_ID = 42L;
	// The watchlist's members — suppression is scoped to these users, not the list (#247)
	private static final List<Long> MEMBER_IDS = List.of(7L, 8L);
	private static final Instant DAY_1 = Instant.parse("2026-07-03T12:00:00Z");
	private static final Instant DAY_2 = Instant.parse("2026-07-04T12:00:00Z");

	// Default tuning (#288) carries every weight at its historical constant value
	// (90-day half-life / 0.2 floor included); test entries are touched within
	// days of DAY_1, so their decay stays ~1 and the pre-#274 score calibrations
	// in these tests still hold
	private SuggestionService serviceAt(Instant now) {
		return serviceAt(now, new SuggestionTuningProperties());
	}

	private SuggestionService serviceAt(Instant now, SuggestionTuningProperties tuning) {
		return new SuggestionService(
			watchlistEntryRepository, watchlistMemberRepository, userRepository, titleService, tmdbClient,
			tmdbTitleCacheRepository, suggestionImpressionService, suggestionDismissalService,
			titleRatingService, Clock.fixed(now, ZoneOffset.UTC), tuning, 30L, 1000L);
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
	void aDominantScoreCandidateRanksFirstEveryDay() {
		// One WATCHING seed (ext1, genre 10 → weight 2) and one WANT_TO_WATCH title
		// (ext2, genre 11 → weight 1) build a sparse graded profile with candidate
		// affinities 0/1/2/3. The proportional jitter (#267) gives the score-3 duals
		// ±0.45 and the score-2 singles ±0.3, so the strongest match tops the shelf
		// every single day — under the old flat ±1.0 band (#248) the daily noise
		// rivaled the whole signal and a weaker candidate could take first place.
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

		for (int d = 0; d < 30; d++) {
			List<String> order = seededShelfOrder(
				serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID));
			assertThat(order).isNotEmpty();
			assertThat(order.get(0)).startsWith("hit-both-");
		}
	}

	@Test
	void nearScoreCandidatesReorderAcrossDaysOnAHeavyProfile() {
		// Genre 10 carries weight 8 (one WATCHING seed plus three WATCHED titles)
		// and genre 11 weight 1 (WANT_TO_WATCH). The strong candidate scores 9
		// (jitter ±1.35) against the close one's 8 (±1.2): a gap of one inside the
		// proportional band (#267), so the pair must swap order on some day — the
		// daily rotation still reorders near-peers, not just exact ties.
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED),
			entry(3, "ext3", WatchStatus.WATCHED),
			entry(4, "ext4", WatchStatus.WATCHED),
			entry(5, "ext5", WatchStatus.WANT_TO_WATCH));
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		stubCacheRows(Map.of(
			"ext1", cacheRow("ext1", List.of(10), null),
			"ext2", cacheRow("ext2", List.of(10), null),
			"ext3", cacheRow("ext3", List.of(10), null),
			"ext4", cacheRow("ext4", List.of(10), null),
			"ext5", cacheRow("ext5", List.of(11), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(scored("strong", List.of(10, 11))); // score 9
		candidates.add(scored("close", List.of(10)));      // score 8
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		boolean strongFirst = false;
		boolean closeFirst = false;
		for (int d = 0; d < 30 && !(strongFirst && closeFirst); d++) {
			List<String> order = seededShelfOrder(
				serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID));
			assertThat(order).isNotEmpty();
			if (order.indexOf("strong") < order.indexOf("close")) {
				strongFirst = true;
			} else {
				closeFirst = true;
			}
		}
		assertThat(strongFirst).isTrue();
		assertThat(closeFirst).isTrue();
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
		// several genres wide so it clears the proportional score jitter (#248/#267,
		// ±0.9 at score 6 vs the fillers' ±0.25 floor) and the hit still ranks
		// first deterministically.
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

	// ── thumbs ratings feeding the taste profile (#273) ────────

	@Test
	void aRatedDownTitlesGenresSinkInShelfRanking() {
		// Mirror of watchedTitlesContributeToGenreProfileScoring, with the WATCHED
		// entry rated down: its genres now carry -2 each instead of +2, so the
		// candidate sharing them scores -6 and drops from first to last — the
		// acceptance criterion for #273.
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of("ext2", cacheRow("ext2", List.of(97, 98, 99), null)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(titleRatingService.effectiveRatings(eq(MEMBER_IDS), any()))
			.thenReturn(Map.of(2L, Rating.DOWN));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(new TitleSearchResponse("rec-disliked-genres", "TMDB", TitleType.TV, "Miss",
			null, null, null, List.of(97, 98, 99)));
		IntStream.rangeClosed(1, 11).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		List<TitleSearchResponse> shelf = shelves.get(0).titles();
		assertThat(shelf).hasSize(12);
		assertThat(shelf.get(11).externalId()).isEqualTo("rec-disliked-genres");
	}

	@Test
	void aRatedUpTitlesGenresOutrankAWatchedTitles() {
		// Rated-up carries weight 4 against WATCHED's inferred 2: the candidate
		// sharing the loved title's genre beats the one sharing the finished
		// title's genre on every day — 4 ∓ 0.6 of proportional jitter never
		// crosses 2 ± 0.3 (#273 calibrated against #267)
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED),
			entry(3, "ext3", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(
			1L, title(1, "ext1"), 2L, title(2, "ext2"), 3L, title(3, "ext3")));
		stubCacheRows(Map.of(
			"ext2", cacheRow("ext2", List.of(10), null),
			"ext3", cacheRow("ext3", List.of(20), null)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(titleRatingService.effectiveRatings(eq(MEMBER_IDS), any()))
			.thenReturn(Map.of(2L, Rating.UP));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(scored("rec-watched-genre", List.of(20))); // score 2
		candidates.add(scored("rec-loved-genre", List.of(10)));   // score 4
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		for (int d = 0; d < 10; d++) {
			List<String> order = seededShelfOrder(
				serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID));
			assertThat(order).isNotEmpty();
			assertThat(order.get(0)).isEqualTo("rec-loved-genre");
			assertThat(order.get(1)).isEqualTo("rec-watched-genre");
		}
	}

	@Test
	void aPersonCarriedOnlyByDislikedTitlesBuildsNoShelf() {
		// Person 500 recurs across two owned titles — enough for the recurrence
		// floor — but both are rated down, so their weighted score is negative
		// and the person drops out of the profile entirely (#273)
		stubWatchlistWithRecurringPerson(
			List.of(new CachedPerson(500, "Ana de Armas")), null);
		when(titleRatingService.effectiveRatings(eq(MEMBER_IDS), any()))
			.thenReturn(Map.of(1L, Rating.DOWN, 2L, Rating.DOWN));

		for (int d = 0; d < 10; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, never()).discoverByPerson(anyInt(), anyInt(), anyInt());
	}

	@Test
	void aKeywordCarriedOnlyByADislikedTitleSeedsNoShelf() {
		// The one owned title carrying the named keyword is rated down: its
		// weighted frequency is negative, so the keyword can't make the profile
		// or seed a shelf (#273)
		stubWatchlistWithNamedKeyword();
		when(titleRatingService.effectiveRatings(eq(MEMBER_IDS), any()))
			.thenReturn(Map.of(1L, Rating.DOWN));

		for (int d = 0; d < 10; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, never()).discoverByKeyword(any(), anyInt(), anyInt(), any(), any(), anyInt());
	}

	// ── recency-decayed taste profile (#274) ──────────────────

	@Test
	void aRecentEntryOutweighsAnOldEntryOfEqualStatus() {
		// Two WATCHED titles of equal status: one touched two days before DAY_1
		// (decay ~1, genre 10 weight ~2) and one a year stale (floored at 0.2,
		// genre 20 weight 0.4). The candidate sharing the recent title's genre
		// outranks the one sharing the old title's every day — ~2 ∓ 0.3 of
		// proportional jitter never crosses 0.4 ± 0.25 (#274 acceptance criterion;
		// pre-decay both scored an identical 2).
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED),
			entry(3, "ext3", WatchStatus.WATCHED));
		entries.get(2).setUpdatedAt(Instant.parse("2025-07-01T00:00:00Z"));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(
			1L, title(1, "ext1"), 2L, title(2, "ext2"), 3L, title(3, "ext3")));
		stubCacheRows(Map.of(
			"ext2", cacheRow("ext2", List.of(10), null),
			"ext3", cacheRow("ext3", List.of(20), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(scored("rec-recent-genre", List.of(10)));
		candidates.add(scored("rec-old-genre", List.of(20)));
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		for (int d = 0; d < 10; d++) {
			List<String> order = seededShelfOrder(
				serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID));
			assertThat(order).isNotEmpty();
			assertThat(order.indexOf("rec-recent-genre")).isLessThan(order.indexOf("rec-old-genre"));
		}
	}

	@Test
	void anAncientEntrysGenresStillContributeAtTheDecayFloor() {
		// A decade-old WATCHED title decays to the 0.2 floor, not to zero: its
		// four genres still give a matching candidate score 4 × 2 × 0.2 = 1.6,
		// clear of the zero-affinity fillers' ±0.25 jitter floor on every day —
		// history still matters, just less (#274)
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHED));
		entries.get(1).setUpdatedAt(Instant.parse("2016-07-01T00:00:00Z"));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of("ext2", cacheRow("ext2", List.of(10, 11, 12, 13), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(scored("rec-floored-hit", List.of(10, 11, 12, 13)));
		IntStream.rangeClosed(1, 11).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		for (int d = 0; d < 10; d++) {
			List<String> order = seededShelfOrder(
				serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID));
			assertThat(order).isNotEmpty();
			assertThat(order.get(0)).isEqualTo("rec-floored-hit");
		}
	}

	@Test
	void decayDropsAnOldTitlesKeywordFromTheProfileCut() {
		// Keyword 900 rides two year-old titles (2 × 0.2 floor = 0.4 weighted)
		// against five keywords riding one fresh title (~1.0 each): the top-5
		// cut keeps the fresh five and drops 900, where the undecayed count
		// (2 vs 1) would have ranked it first — so the keyword-shelf draw can
		// never seed on it (#274 applies to the keyword profile too)
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WATCHING),
			entry(3, "ext3", WatchStatus.WATCHING));
		entries.get(0).setUpdatedAt(Instant.parse("2025-07-01T00:00:00Z"));
		entries.get(1).setUpdatedAt(Instant.parse("2025-07-01T00:00:00Z"));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(
			1L, title(1, "ext1"), 2L, title(2, "ext2"), 3L, title(3, "ext3")));
		stubCacheRows(Map.of(
			"ext1", keywordRow("ext1", List.of(900), List.of(new CachedKeyword(900, "time travel"))),
			"ext2", keywordRow("ext2", List.of(900), List.of(new CachedKeyword(900, "time travel"))),
			"ext3", keywordRow("ext3", List.of(100, 200, 300, 400, 500), List.of(
				new CachedKeyword(100, "heist"),
				new CachedKeyword(200, "space race"),
				new CachedKeyword(300, "con artist"),
				new CachedKeyword(400, "slow burn"),
				new CachedKeyword(500, "road trip")))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.discoverByKeyword(any(), anyInt(), anyInt(), any(), any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12)
				.mapToObj(i -> candidate("kw-" + inv.getArgument(1) + "-" + i)).toList());

		for (int d = 0; d < 15; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> keywordId = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).discoverByKeyword(any(), keywordId.capture(), anyInt(), any(), any(), anyInt());
		assertThat(keywordId.getAllValues()).isNotEmpty().doesNotContain(900);
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
	void sharedTopPersonOutranksAnOtherwiseEqualCandidate() {
		// Person 500 recurs across two owned titles — at the recurrence floor, so
		// they enter the person profile (#269). The candidate whose cache row shares
		// them gets the 3.0 person boost, which clears the jitter floor (±0.25 for
		// the signal-less peers, ±0.45 for the boosted one) on every day.
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WANT_TO_WATCH));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of(
			"ext1", personRow("ext1", List.of(new CachedPerson(500, "Ana de Armas")), null),
			"ext2", personRow("ext2", List.of(new CachedPerson(500, "Ana de Armas")), null),
			"rec-person", personRow("rec-person", List.of(new CachedPerson(500, "Ana de Armas")), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(candidate("rec-plain"));
		candidates.add(candidate("rec-person"));
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.PER_SEED);
			assertThat(shelf.titles().get(0).externalId()).isEqualTo("rec-person");
		});
	}

	@Test
	void aSingleAppearancePersonBuildsNoProfileOrShelf() {
		// Every person appears in exactly one owned title: below the recurrence
		// floor, so no person shelf is attempted no matter which exploration kinds
		// the daily rotation tries (#269)
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WANT_TO_WATCH));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of(
			"ext1", personRow("ext1", List.of(new CachedPerson(500, "Ana de Armas")), null),
			"ext2", personRow("ext2", List.of(new CachedPerson(501, "Oscar Isaac")), null)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		for (int d = 0; d < 10; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, never()).discoverByPerson(anyInt(), anyInt(), anyInt());
	}

	@Test
	void personShelfIsLabeledWithTheRecurringActor() {
		// The recurring actor is the only signal that can fill a shelf: no genres,
		// empty recommendation feeds, empty trending — the PERSON kind gets its
		// exploration slot and the label carries the person's name (#269)
		stubWatchlistWithRecurringPerson(
			List.of(new CachedPerson(500, "Ana de Armas")), null);
		when(tmdbClient.discoverByPerson(eq(500), anyInt(), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("p-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.PERSON);
		assertThat(shelves.get(0).reason()).isEqualTo("More with Ana de Armas");
		assertThat(shelves.get(0).titles()).hasSize(12);
		// Movie-only discover, modest vote floor, always page 1 (#269)
		verify(tmdbClient).discoverByPerson(500, 50, 1);
	}

	@Test
	void personShelfSaysDirectedByForARecurringDirector() {
		stubWatchlistWithRecurringPerson(
			null, List.of(new CachedPerson(525, "Denis Villeneuve")));
		when(tmdbClient.discoverByPerson(eq(525), anyInt(), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("p-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.PERSON);
		assertThat(shelves.get(0).reason()).isEqualTo("Directed by Denis Villeneuve");
	}

	// Two owned titles sharing the given cast/directors (clearing the recurrence
	// floor) and nothing else: no genres, no keywords, empty seed feeds — only the
	// PERSON exploration kind can produce a shelf
	private void stubWatchlistWithRecurringPerson(List<CachedPerson> topCast, List<CachedPerson> directors) {
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHING),
			entry(2, "ext2", WatchStatus.WANT_TO_WATCH));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1"), 2L, title(2, "ext2")));
		stubCacheRows(Map.of(
			"ext1", personRow("ext1", topCast, directors),
			"ext2", personRow("ext2", topCast, directors)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
	}

	private TmdbTitleCache personRow(String tmdbId, List<CachedPerson> topCast, List<CachedPerson> directors) {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(tmdbId);
		c.setTopCast(topCast);
		c.setDirectors(directors);
		return c;
	}

	// ── keyword-seeded shelves (#271) ─────────────────────────

	@Test
	void keywordShelfIsLabeledWithTheKeywordName() {
		// The cached keyword is the only signal that can fill a shelf: no genres,
		// empty recommendation feeds, empty trending — the KEYWORD kind gets its
		// exploration slot and the label carries the keyword's display name (#271)
		stubWatchlistWithNamedKeyword();
		when(tmdbClient.discoverByKeyword(eq(TitleType.TV), eq(9882), anyInt(), any(), any(), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("kw-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.KEYWORD);
		assertThat(shelves.get(0).reason()).isEqualTo("Space race stories");
		assertThat(shelves.get(0).titles()).hasSize(12);
		// Dominant-type discover with the standard vote floor and a shallow page
		// draw (#271) — a single keyword's catalog thins fast
		verify(tmdbClient).discoverByKeyword(eq(TitleType.TV), eq(9882), eq(100),
			isNull(), isNull(), intThat(p -> p >= 1 && p <= 3));
	}

	@Test
	void keywordShelfUsesMoviesSuffixForAMovieLeaningList() {
		Title movie = title(1, "ext1");
		movie.setType(TitleType.MOVIE);
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, movie));
		stubCacheRows(Map.of("ext1",
			keywordRow("ext1", List.of(1701), List.of(new CachedKeyword(1701, "heist")))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.discoverByKeyword(eq(TitleType.MOVIE), eq(1701), anyInt(), any(), any(), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("kw-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).reason()).isEqualTo("Heist movies");
	}

	@Test
	void keywordShelfYieldsWhenNoNamesAreCached() {
		// Pre-V20 rows carry keyword ids but no names: the ids still boost scoring,
		// but a shelf can't be labeled, so the kind yields its exploration slot on
		// every day's rotation (#271)
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", null, List.of(9882))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		for (int d = 0; d < 10; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, never()).discoverByKeyword(any(), anyInt(), anyInt(), any(), any(), anyInt());
	}

	@Test
	void keywordShelfRotatesAcrossDaysButIsStableWithinADay() {
		// Three named keywords tie at one appearance each: the day-seeded draw must
		// reach more than one of them across days, while two computes on the same
		// day serve identical shelves (#271 acceptance criteria)
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", keywordRow("ext1", List.of(100, 200, 300), List.of(
			new CachedKeyword(100, "heist"),
			new CachedKeyword(200, "space race"),
			new CachedKeyword(300, "con artist")))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.discoverByKeyword(any(), anyInt(), anyInt(), any(), any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12)
				.mapToObj(i -> candidate("kw-" + inv.getArgument(1) + "-" + i)).toList());

		assertThat(serviceAt(DAY_1).topPicks(WATCHLIST_ID))
			.isEqualTo(serviceAt(DAY_1).topPicks(WATCHLIST_ID));

		for (int d = 0; d < 15; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> keywordId = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).discoverByKeyword(any(), keywordId.capture(), anyInt(), any(), any(), anyInt());
		assertThat(new HashSet<>(keywordId.getAllValues())).hasSizeGreaterThan(1);
	}

	@Test
	void keywordShelfTakesTheProviderFilter() {
		// Keyword discover is a discover-backed feed, so it takes TMDB's provider
		// filter directly and the shelf is marked providerFiltered (#270 conventions)
		stubWatchlistWithNamedKeyword();
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, "US", List.of(8))));
		when(tmdbClient.discoverByKeyword(eq(TitleType.TV), eq(9882), anyInt(), eq("US"), eq(List.of(8)), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("kw-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.KEYWORD);
		assertThat(shelves.get(0).providerFiltered()).isTrue();
	}

	// One owned title whose only cached signal is a named keyword: no genres, no
	// people, empty seed feeds — only the KEYWORD exploration kind can fill
	private void stubWatchlistWithNamedKeyword() {
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1",
			keywordRow("ext1", List.of(9882), List.of(new CachedKeyword(9882, "space race")))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
	}

	private TmdbTitleCache keywordRow(String tmdbId, List<Integer> keywordIds, List<CachedKeyword> keywords) {
		TmdbTitleCache c = cacheRow(tmdbId, null, keywordIds);
		c.setKeywords(keywords);
		return c;
	}

	// ── franchise-continuation shelf (#272) ────────────────────

	@Test
	void franchiseShelfIsLabeledWithTheCollectionNameAndOrderedByReleaseDate() {
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(
			moviePart("part-later", "2024-11-01"),
			moviePart("part-earlier", "2021-10-22")));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.FRANCHISE);
		assertThat(shelves.get(0).reason()).isEqualTo("Next in the Dune Collection");
		assertThat(shelves.get(0).titles()).extracting(TitleSearchResponse::externalId)
			.containsExactly("part-earlier", "part-later");
	}

	@Test
	void ownedPartsAreExcludedFromTheFranchiseShelf() {
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		// The seed movie itself comes back as one of the collection's parts —
		// already in the watchlist, so it must not double up on the shelf
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(
			moviePart("ext1", "2021-10-22"),
			moviePart("part-later", "2024-11-01")));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).titles()).extracting(TitleSearchResponse::externalId)
			.containsExactly("part-later");
	}

	@Test
	void aSingleRemainingPartStillBuildsAFranchiseShelf() {
		// Acceptance criteria (#272): a lone remaining sequel is still the single
		// best suggestion available — no MIN_SHELF_SIZE floor like other shelves
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(moviePart("part-later", "2024-11-01")));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).titles()).hasSize(1);
	}

	@Test
	void franchiseShelfDisappearsOnceTheCollectionIsComplete() {
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		// Every part TMDB knows about is already owned
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(moviePart("ext1", "2021-10-22")));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).noneMatch(s -> s.kind() == SuggestionShelfResponse.ShelfKind.FRANCHISE);
	}

	@Test
	void noCachedCollectionBuildsNoFranchiseShelf() {
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		Title movie = title(1, "ext1");
		movie.setType(TitleType.MOVIE);
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, movie));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", null, null)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		verify(tmdbClient, never()).getCollectionParts(anyInt());
	}

	@Test
	void wantToWatchEntriesDoNotSeedAFranchiseShelf() {
		// Franchise continuation is a completed-interest signal (#272), not a
		// taste-profile one — only WATCHED/WATCHING entries qualify
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WANT_TO_WATCH));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		Title movie = title(1, "ext1");
		movie.setType(TitleType.MOVIE);
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, movie));
		stubCacheRows(Map.of("ext1", collectionRow("ext1", 10, "Dune Collection")));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		verify(tmdbClient, never()).getCollectionParts(anyInt());
	}

	@Test
	void franchiseShelfRotatesAcrossDaysButIsStableWithinADay() {
		// Three owned movies from different collections, tied at one appearance
		// each: the day-seeded draw must reach more than one across days, while
		// two computes on the same day pick the same collection (#231/#272)
		List<WatchlistEntry> entries = List.of(
			entry(1, "ext1", WatchStatus.WATCHED),
			entry(2, "ext2", WatchStatus.WATCHED),
			entry(3, "ext3", WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		Title movie1 = title(1, "ext1");
		movie1.setType(TitleType.MOVIE);
		Title movie2 = title(2, "ext2");
		movie2.setType(TitleType.MOVIE);
		Title movie3 = title(3, "ext3");
		movie3.setType(TitleType.MOVIE);
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, movie1, 2L, movie2, 3L, movie3));
		stubCacheRows(Map.of(
			"ext1", collectionRow("ext1", 10, "Dune Collection"),
			"ext2", collectionRow("ext2", 20, "John Wick Collection"),
			"ext3", collectionRow("ext3", 30, "The Matrix Collection")));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.getCollectionParts(anyInt())).thenAnswer(inv ->
			List.of(moviePart("part-" + inv.getArgument(0), "2024-01-01")));

		assertThat(serviceAt(DAY_1).topPicks(WATCHLIST_ID))
			.isEqualTo(serviceAt(DAY_1).topPicks(WATCHLIST_ID));

		for (int d = 0; d < 15; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> collectionId = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).getCollectionParts(collectionId.capture());
		assertThat(new HashSet<>(collectionId.getAllValues())).hasSizeGreaterThan(1);
	}

	@Test
	void franchiseShelfKeepsReleaseOrderDespiteRecencyPenalties() {
		// The earlier part was shown recently on some other shelf (full weight);
		// under fillShelf's demotion it would sink below the later part, breaking
		// chronology. The franchise shelf is exempt (#272) — release order is its
		// whole ordering contract, and staying visible is the feature.
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(
			moviePart("part-earlier", "2021-10-22"),
			moviePart("part-later", "2024-11-01")));
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(Map.of("part-earlier", 1.0));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).titles()).extracting(TitleSearchResponse::externalId)
			.containsExactly("part-earlier", "part-later");
	}

	@Test
	void unreleasedPartsAreExcludedFromTheFranchiseShelf() {
		// A future-dated part can't be watched yet, and a date-less part is
		// unannounced — neither is a suggestion the user can act on (#272).
		// DAY_1 is 2026-07-03, so the 2027 part is unreleased.
		stubWatchedMovieWithCollection("ext1", 10, "Dune Collection");
		when(tmdbClient.getCollectionParts(10)).thenReturn(List.of(
			moviePart("part-released", "2024-11-01"),
			moviePart("part-future", "2027-06-01"),
			new TitleSearchResponse("part-undated", "TMDB", TitleType.MOVIE, "Part part-undated",
				null, null, null, List.of())));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).titles()).extracting(TitleSearchResponse::externalId)
			.containsExactly("part-released");
	}

	// One WATCHED movie with a cached collection id/name: no genres, no people,
	// empty seed feeds — only the franchise shelf can fill
	private void stubWatchedMovieWithCollection(String externalId, int collectionId, String collectionName) {
		List<WatchlistEntry> entries = List.of(entry(1, externalId, WatchStatus.WATCHED));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		Title movie = title(1, externalId);
		movie.setType(TitleType.MOVIE);
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, movie));
		stubCacheRows(Map.of(externalId, collectionRow(externalId, collectionId, collectionName)));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
	}

	private TmdbTitleCache collectionRow(String tmdbId, int collectionId, String collectionName) {
		TmdbTitleCache c = cacheRow(tmdbId, null, null);
		c.setCollectionId(collectionId);
		c.setCollectionName(collectionName);
		return c;
	}

	private TitleSearchResponse moviePart(String externalId, String releaseDate) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.MOVIE, "Part " + externalId,
			null, LocalDate.parse(releaseDate), null, List.of());
	}

	@Test
	void dismissedTitlesAreExcludedFromEveryShelf() {
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		// One dismissed candidate per potential seed: unlike the recency penalty,
		// a dismissal is a hard exclusion (#268) — the title never enters a shelf,
		// no matter how thin the pool
		Set<String> dismissed = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> "rec-ext" + i + "-1")
			.collect(Collectors.toSet());
		when(suggestionDismissalService.dismissedTmdbIds(MEMBER_IDS)).thenReturn(dismissed);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).isNotEmpty();
		assertThat(shelves).allSatisfy(shelf -> {
			// The shelf still fills from the remaining pool, minus the dismissed title
			assertThat(shelf.titles()).hasSize(11);
			assertThat(shelf.titles().stream().map(TitleSearchResponse::externalId))
				.noneMatch(dismissed::contains);
		});
	}

	@Test
	void aDismissalFromOneMemberExcludesTheTitleForTheSharedList() {
		// compute asks for the union over MEMBER_IDS (#247's scoping rule), so a
		// title only one member dismissed is in the returned set and never served
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		verify(suggestionDismissalService).dismissedTmdbIds(MEMBER_IDS);
	}

	@Test
	void evictForUserDropsCachedShelvesForEveryListTheUserBelongsTo() {
		stubEmptyWatchlist();
		when(watchlistMemberRepository.findWatchlistIdsByUserId(7L)).thenReturn(List.of(WATCHLIST_ID));
		SuggestionService service = serviceAt(DAY_1);

		service.topPicks(WATCHLIST_ID);
		service.evictForUser(7L);
		service.topPicks(WATCHLIST_ID);

		// The second read recomputes instead of serving the pre-dismissal cache
		verify(watchlistEntryRepository, times(2))
			.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class));
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
		// (#264) the whole pool is served, fresh candidates first. With only two
		// fresh titles the pool also misses the standalone fresh floor (#266), so
		// it surfaces via the catch-all shelf rather than its own.
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
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		List<String> shelfIds = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		assertThat(shelfIds).hasSize(5);
		assertThat(shelfIds.subList(0, 2)).containsExactlyInAnyOrder("rec-4", "rec-5");
		assertThat(shelfIds.subList(2, 5)).containsExactlyInAnyOrder("rec-1", "rec-2", "rec-3");
	}

	@Test
	void recencyPenaltyDecaysFromYesterdayToTheWindowEdge() {
		// A graded profile (genres 10–13, weight 2 each) pins the base ranking beyond
		// the proportional jitter: shown-yesterday (affinity 8) ranks first, shown-week-ago
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
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(20), eq("popularity.desc"), notNull(), notNull(), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("nr-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.NEW_RELEASES);
			assertThat(shelf.reason()).isEqualTo("New in your genres");
		});
		// The window is the 60 days ending "today" on the fixed clock
		verify(tmdbClient, atLeastOnce()).discover(eq(TitleType.TV), eq(List.of(99)), eq(List.of()), eq(20),
			eq("popularity.desc"), eq(LocalDate.of(2026, 5, 4)), eq(LocalDate.of(2026, 7, 3)), isNull(), isNull(), anyInt());
	}

	// ── Hidden gems (#376) ────────────────────────────────────

	@Test
	void hiddenGemsShelfSortsByRatingWithModerateVoteFloor() {
		stubPopulatedWatchlistWithGenres();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		stubHiddenGemsDiscover(IntStream.rangeClosed(1, 12).mapToObj(i -> gem("gem-" + i, 5.0)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
			assertThat(shelf.reason()).isEqualTo("Hidden gems for you");
		});
		// Well-rated rather than popular, and off the long tail of barely-rated titles
		verify(tmdbClient, atLeastOnce()).discover(eq(TitleType.TV), eq(List.of(99)), eq(List.of()),
			eq(200), eq("vote_average.desc"), isNull(), isNull(), isNull(), isNull(), anyInt());
	}

	@Test
	void hiddenGemsExcludesTitlesAtOrAboveThePopularityCeiling() {
		// The default ceiling is 20.0 and the bound is strict: 19.9 is a gem,
		// 20.0 is not. Eight passers keep the shelf clear of MIN_SHELF_SIZE so a
		// short shelf can only mean the gate cut something it shouldn't have.
		stubPopulatedWatchlistWithGenres();
		List<TitleSearchResponse> page = new ArrayList<>();
		IntStream.rangeClosed(1, 8).forEach(i -> page.add(gem("gem-under-" + i, 19.9)));
		IntStream.rangeClosed(1, 8).forEach(i -> page.add(gem("gem-at-" + i, 20.0)));
		IntStream.rangeClosed(1, 4).forEach(i -> page.add(gem("gem-over-" + i, 412.5)));
		stubHiddenGemsDiscover(page);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(hiddenGemIds(shelves)).hasSize(8).allSatisfy(id ->
			assertThat(id).startsWith("gem-under-"));
	}

	@Test
	void hiddenGemsExcludesCandidatesWithUnknownPopularity() {
		// Only TMDB-sourced feeds populate popularity (#374). A null can't answer
		// the one question this shelf asks, so it is excluded rather than admitted
		// — admitting it would let the most popular titles in through the gap.
		stubPopulatedWatchlistWithGenres();
		List<TitleSearchResponse> page = new ArrayList<>();
		IntStream.rangeClosed(1, 4).forEach(i -> page.add(gem("gem-known-" + i, 3.0)));
		IntStream.rangeClosed(1, 12).forEach(i -> page.add(scored("gem-unknown-" + i, List.of(99))));
		stubHiddenGemsDiscover(page);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(hiddenGemIds(shelves)).hasSize(4).allSatisfy(id ->
			assertThat(id).startsWith("gem-known-"));
	}

	@Test
	void hiddenGemsBailsWithoutFetchingWhenThereIsNoGenreProfile() {
		// stubPopulatedWatchlist (no cache rows) leaves dominantGenres empty. The
		// bail must also come before the page draw — that half is guarded by the
		// tuning harness's re-baseline invariant (nothing above the exploration
		// stage may move), the same mechanism #375 used, since a rng draw is not
		// observable through the TmdbClient mock.
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));
		stubHiddenGemsDiscover(IntStream.rangeClosed(1, 12).mapToObj(i -> gem("gem-" + i, 1.0)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).noneMatch(s -> s.kind() == SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
		verify(tmdbClient, never()).discover(any(), any(), any(), eq(200), eq("vote_average.desc"),
			any(), any(), any(), any(), anyInt());
	}

	@Test
	void hiddenGemsShelfIsProviderFilteredForAConfiguredMember() {
		// One member, so the #322 both-watch shelf can't claim the pool first
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(7L));
		when(userRepository.findAllById(List.of(7L)))
			.thenReturn(List.of(providerUser(7L, "US", List.of(8, 9))));
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(200), eq("vote_average.desc"),
				isNull(), isNull(), eq("US"), notNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> gem("gem-" + i, 4.0)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
			assertThat(shelf.providerFiltered()).isTrue();
		});
	}

	@Test
	void hiddenGemsCeilingDoesNotChangeRngConsumption() {
		// The load-bearing half of "rank first, gate second". jitterByCandidate
		// draws once per distinct candidate, so gating before ranking would make
		// the draw count a function of the ceiling *value* — and every shelf built
		// after this stage would then move for every user whenever the ceiling was
		// re-tuned. Ranking the full page pins the draw count to page size.
		//
		// Trending is stubbed from a disjoint pool, so its shelf can only differ if
		// the rng stream reached it at a different position.
		stubPopulatedWatchlistWithGenres();
		stubHiddenGemsDiscover(IntStream.rangeClosed(1, 20)
			.mapToObj(i -> gem("gem-" + i, i * 2.0))
			.toList());
		lenient().when(tmdbClient.getTrending(eq(TitleType.TV), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> scored("trend-" + i, List.of(99))).toList());

		List<SuggestionShelfResponse> strict = serviceAt(DAY_1, ceiling(10.0)).topPicks(WATCHLIST_ID);
		List<SuggestionShelfResponse> loose = serviceAt(DAY_1, ceiling(30.0)).topPicks(WATCHLIST_ID);

		// The ceilings genuinely differ, so the run below is a real comparison
		assertThat(hiddenGemIds(strict)).hasSize(4);
		assertThat(hiddenGemIds(loose)).hasSize(12);
		// ...yet everything downstream of the gate is untouched
		assertThat(shelfIds(loose, SuggestionShelfResponse.ShelfKind.TRENDING))
			.isEqualTo(shelfIds(strict, SuggestionShelfResponse.ShelfKind.TRENDING))
			.isNotEmpty();
	}

	private SuggestionTuningProperties ceiling(double popularityCeiling) {
		SuggestionTuningProperties tuning = new SuggestionTuningProperties();
		tuning.setHiddenGemPopularityCeiling(popularityCeiling);
		return tuning;
	}

	private void stubHiddenGemsDiscover(List<TitleSearchResponse> page) {
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(200), eq("vote_average.desc"),
				isNull(), isNull(), isNull(), isNull(), anyInt()))
			.thenReturn(page);
	}

	// A candidate carrying the profile genre and an explicit TMDB popularity —
	// candidate()/scored() use the shorter constructors, which leave it null (#374)
	private TitleSearchResponse gem(String externalId, double popularity) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.TV, "Title " + externalId,
			null, null, null, List.of(99), null, popularity);
	}

	private List<String> hiddenGemIds(List<SuggestionShelfResponse> shelves) {
		return shelfIds(shelves, SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS);
	}

	private List<String> shelfIds(List<SuggestionShelfResponse> shelves,
			SuggestionShelfResponse.ShelfKind kind) {
		return shelves.stream()
			.filter(s -> s.kind() == kind)
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.toList();
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
		when(tmdbClient.discover(any(), any(), any(), anyInt(), anyString(), any(), any(), any(), any(), anyInt()))
			.thenAnswer(inv -> {
				int b = batch.incrementAndGet();
				return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("d" + b + "-" + i)).toList();
			});
		lenient().when(tmdbClient.getTrending(any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("t-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		Set<SuggestionShelfResponse.ShelfKind> exploration = Set.of(
			SuggestionShelfResponse.ShelfKind.NEW_RELEASES,
			SuggestionShelfResponse.ShelfKind.TRENDING,
			SuggestionShelfResponse.ShelfKind.PERSON,
			SuggestionShelfResponse.ShelfKind.KEYWORD);
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
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"), isNull(), isNull(), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gp-" + i)).toList());

		for (int d = 0; d < 40; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
		verify(tmdbClient, atLeastOnce()).discover(any(), any(), any(), eq(100), eq("popularity.desc"),
			isNull(), isNull(), isNull(), isNull(), page.capture());
		assertThat(page.getAllValues()).allSatisfy(p -> assertThat(p).isBetween(1, 6));
		// Across days the draw reaches past the old 3-page ceiling
		assertThat(page.getAllValues()).anyMatch(p -> p > 3);
	}

	@Test
	void sameGenreDiscoverPageFillsTheShelfWithoutGenreCapping() {
		// Genre-profile discover is filtered to the user's top genres by construction,
		// so a full page shares one genre; exempt from the cluster cap (#265), the
		// shelf fills to MAX_SHELF_SIZE instead of being chopped to ~4
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"), isNull(), isNull(), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("gp-" + i, List.of(99))).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.GENRE_PROFILE);
		assertThat(shelves.get(0).titles()).hasSize(12);
	}

	@Test
	void discoverExplorationShelvesSkipTheCapButTrendingKeepsIt() {
		// New releases is discover-backed (genre-filtered → exempt, #265) while
		// trending carries a real genre mix and stays diversified
		stubPopulatedWatchlistWithGenres();
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(20), eq("popularity.desc"), notNull(), notNull(), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("nr-" + i, List.of(99))).toList());
		lenient().when(tmdbClient.getTrending(any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 20).mapToObj(i -> scored("t-" + i, List.of(99))).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.NEW_RELEASES);
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
		// candidates sharing their only genre is capped at MAX_PER_GENRE_CLUSTER.
		// A cap-chopped shelf reads as a stub too, so it misses the fresh floor
		// (#266) and its pool surfaces via the equally capped catch-all instead.
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
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		assertThat(shelves.get(0).titles()).hasSize(4);
	}

	@Test
	void anUnderCapSecondaryGenreAdmitsACandidatePastThePrimaryGenreCap() {
		// The cap keys on the candidate's full genre set, not TMDB's arbitrary first
		// genre id (#265): once genres 10/11/50 saturate on the high-affinity pure
		// candidates, the low-affinity dual still enters through its fresh genre 60.
		// (At five titles the shelf misses the fresh floor and serves via the
		// catch-all (#266); the cap behaves identically there.)
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", List.of(10, 11), null)));

		// Pures score 4 (genres 10+11, weight 2 each), the dual scores 0 — a gap
		// beyond the proportional jitter, so exactly four pures rank ahead of the dual
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

	@Test
	void thinSeedFeedFoldsIntoTheCatchAllShelf() {
		// A seed whose whole feed is five titles can't reach the standalone fresh
		// floor (#266): it produces no PER_SEED shelf, but its candidates still
		// surface via the pooled catch-all
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 5).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		assertThat(shelves.get(0).reason()).isEqualTo("More picks for you");
		assertThat(shelves.get(0).titles().stream().map(TitleSearchResponse::externalId))
			.containsExactlyInAnyOrder("rec-1", "rec-2", "rec-3", "rec-4", "rec-5");
	}

	@Test
	void multipleThinSeedShelvesPoolIntoOneCatchAll() {
		// Three seeds with four-title feeds each: instead of three stub shelves,
		// one aggregated MORE_PICKS shelf carries all twelve candidates (#266)
		stubPopulatedWatchlist();
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 4)
				.mapToObj(i -> candidate("rec-" + inv.getArgument(1) + "-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		assertThat(shelves.get(0).titles()).hasSize(12);
	}

	@Test
	void aFullButMostlyReServedShelfFoldsIntoTheCatchAll() {
		// The floor counts fresh titles only (#266): a shelf that fills to twelve
		// but carries just five titles the user hasn't seen this window is a
		// repeat-heavy stub and folds into the catch-all
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of());
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt()))
			.thenReturn(IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("rec-" + i)).toList());
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(suggestionImpressionService.recencyWeights(MEMBER_IDS))
			.thenReturn(IntStream.rangeClosed(1, 7).boxed()
				.collect(Collectors.toMap(i -> "rec-" + i, i -> 1.0)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		List<String> ids = shelves.get(0).titles().stream()
			.map(TitleSearchResponse::externalId).toList();
		// The catch-all still serves the whole pool, fresh titles first
		assertThat(ids).hasSize(12);
		assertThat(ids.subList(0, 5))
			.containsExactlyInAnyOrder("rec-8", "rec-9", "rec-10", "rec-11", "rec-12");
	}

	@Test
	void catchAllRanksPooledCandidatesByTasteProfile() {
		// The pooled shelf reuses the taste-profile scoring: the owned title's
		// cached genres 10–13 (weight 2 each) give the hit affinity 8, clear of
		// the proportional jitter, so it ranks first among the pooled leftovers (#266)
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		stubCacheRows(Map.of("ext1", cacheRow("ext1", List.of(10, 11, 12, 13), null)));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		IntStream.rangeClosed(1, 4).forEach(i -> candidates.add(candidate("filler-" + i)));
		candidates.add(scored("hit", List.of(10, 11, 12, 13)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).hasSize(1);
		assertThat(shelves.get(0).kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.MORE_PICKS);
		assertThat(shelves.get(0).titles().get(0).externalId()).isEqualTo("hit");
	}

	@Test
	void richSeedsArePreferredOverThinSeeds() {
		// ext1–ext3 carry cached vote counts above the rich floor; ext4/ext5 have
		// no cache row. With exactly MAX_SEEDS rich seeds, every daily draw must
		// pick the rich ones (#266) — vote count proxies feed depth.
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> entry(i, "ext" + i))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		stubCacheRows(IntStream.rangeClosed(1, 3).boxed()
			.collect(Collectors.toMap(i -> "ext" + i, i -> cacheRow("ext" + i, null, null, 1000))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		for (int d = 0; d < 10; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		verify(tmdbClient, atLeastOnce()).getRecommendations(any(), eq("ext1"), anyInt());
		verify(tmdbClient, atLeastOnce()).getRecommendations(any(), eq("ext2"), anyInt());
		verify(tmdbClient, atLeastOnce()).getRecommendations(any(), eq("ext3"), anyInt());
		verify(tmdbClient, never()).getRecommendations(any(), eq("ext4"), anyInt());
		verify(tmdbClient, never()).getRecommendations(any(), eq("ext5"), anyInt());
	}

	@Test
	void richSeedsRotateAcrossDaysRatherThanPinning() {
		// Six seeds all above the rich floor: the daily shuffle within the rich
		// tier must reach more than MAX_SEEDS distinct seeds across days (#266) —
		// richness preference must not pin the same top few
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 6)
			.mapToObj(i -> entry(i, "ext" + i))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 6)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		stubCacheRows(IntStream.rangeClosed(1, 6).boxed()
			.collect(Collectors.toMap(i -> "ext" + i, i -> cacheRow("ext" + i, null, null, 500 + i))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		for (int d = 0; d < 15; d++) {
			serviceAt(DAY_1.plus(Duration.ofDays(d))).topPicks(WATCHLIST_ID);
		}

		ArgumentCaptor<String> seedIds = ArgumentCaptor.forClass(String.class);
		verify(tmdbClient, atLeastOnce()).getRecommendations(any(), seedIds.capture(), anyInt());
		assertThat(new HashSet<>(seedIds.getAllValues())).hasSizeGreaterThan(3);
	}

	// Like stubPopulatedWatchlist, but every owned title carries cached genre 99,
	// enabling genre-profile and exploration shelves
	private void stubPopulatedWatchlistWithGenres() {
		stubPopulatedWatchlistWithGenres(Map.of());
	}

	// stubCacheRows can only be called once per test — a second when(findAllById(any()))
	// re-invokes the first stub's answer with a null argument — so extra candidate rows
	// have to go in through here rather than a follow-up call
	private void stubPopulatedWatchlistWithGenres(Map<String, TmdbTitleCache> candidateRows) {
		List<WatchlistEntry> entries = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> entry(i, "ext" + i))
			.toList();
		Map<Long, Title> titles = IntStream.rangeClosed(1, 5)
			.mapToObj(i -> title(i, "ext" + i))
			.collect(Collectors.toMap(Title::getId, Function.identity()));

		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(titles);
		Map<String, TmdbTitleCache> rows = new HashMap<>(IntStream.rangeClosed(1, 5).boxed()
			.collect(Collectors.toMap(i -> "ext" + i, i -> cacheRow("ext" + i, List.of(99), null))));
		rows.putAll(candidateRows);
		stubCacheRows(rows);
	}

	// ── Watch-provider awareness (#270) ───────────────────────

	@Test
	void discoverShelvesAreProviderFilteredWhenMembersShareARegion() {
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		// Two members, same region, overlapping services — the filter carries
		// the union of provider ids
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, "US", List.of(8, 9)),
			providerUser(8L, "US", List.of(8, 337))));
		// These members also qualify for the #322 both-watch shelf, which discovers
		// against the intersection {8} and gets first pick of the dedup set. Give it
		// its own pool so it doesn't eat the genre shelf's candidates — in production
		// the two filters hit TMDB with different provider sets and get different results.
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"),
				isNull(), isNull(), eq("US"), any(), anyInt()))
			.thenAnswer(inv -> {
				List<Integer> providerIds = inv.getArgument(8);
				String pool = new HashSet<>(providerIds).equals(Set.of(8)) ? "bw-" : "gp-";
				return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate(pool + i)).toList();
			});

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.GENRE_PROFILE);
			assertThat(shelf.providerFiltered()).isTrue();
		});
		verify(tmdbClient, atLeastOnce()).discover(any(), any(), any(), eq(100), eq("popularity.desc"),
			isNull(), isNull(), eq("US"),
			argThat((List<Integer> ids) -> ids != null && new HashSet<>(ids).equals(Set.of(8, 9, 337))),
			anyInt());
	}

	@Test
	void conflictingMemberRegionsDisableProviderAwareness() {
		// Availability is region-scoped and discover takes one watch_region:
		// members in different regions have no single truthful answer, so the
		// list falls back to provider-blind behavior (#270)
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, "US", List.of(8)),
			providerUser(8L, "GB", List.of(8))));
		lenient().when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"),
				isNull(), isNull(), isNull(), isNull(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gp-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.GENRE_PROFILE);
			assertThat(shelf.providerFiltered()).isFalse();
		});
		verify(tmdbClient, never()).discover(any(), any(), any(), anyInt(), anyString(),
			any(), any(), notNull(), notNull(), anyInt());
	}

	@Test
	void streamableCandidateOutranksAnOtherwiseEqualOne() {
		// Recommendations take no provider filter, so streamability enters as a
		// score boost (#270): 2.5 clears the jitter floor (±0.25 signal-less,
		// ±0.375 for the boosted candidate) on every day
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(7L));
		when(userRepository.findAllById(List.of(7L)))
			.thenReturn(List.of(providerUser(7L, "US", List.of(8))));
		stubCacheRows(Map.of("rec-stream", providerRow("rec-stream", Map.of("US", List.of(8)))));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(candidate("rec-plain"));
		candidates.add(candidate("rec-stream"));
		IntStream.rangeClosed(1, 10).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.PER_SEED);
			assertThat(shelf.titles().get(0).externalId()).isEqualTo("rec-stream");
		});
	}

	@Test
	void servedTitlesCarryProviderBadgesIntersectedWithMemberServices() {
		List<WatchlistEntry> entries = List.of(entry(1, "ext1", WatchStatus.WATCHING));
		when(watchlistEntryRepository.findByWatchlistId(eq(WATCHLIST_ID), any(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(entries));
		when(titleService.findByIds(any())).thenReturn(Map.of(1L, title(1, "ext1")));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(7L));
		when(userRepository.findAllById(List.of(7L)))
			.thenReturn(List.of(providerUser(7L, "US", List.of(8))));
		// Streamable on service 8 (the user's) and 99 (not theirs); the badge
		// carries only the intersection (#270)
		stubCacheRows(Map.of("rec-stream", providerRow("rec-stream", Map.of("US", List.of(8, 99)))));

		List<TitleSearchResponse> candidates = new ArrayList<>();
		candidates.add(candidate("rec-stream"));
		IntStream.rangeClosed(1, 11).forEach(i -> candidates.add(candidate("rec-filler-" + i)));
		when(tmdbClient.getRecommendations(any(), eq("ext1"), anyInt())).thenReturn(candidates);

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		TitleSearchResponse streamable = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.filter(t -> t.externalId().equals("rec-stream"))
			.findFirst().orElseThrow();
		assertThat(streamable.providerIds()).containsExactly(8);
		// Titles without cached provider data keep a null ("unknown") badge
		TitleSearchResponse plain = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.filter(t -> t.externalId().startsWith("rec-filler"))
			.findFirst().orElseThrow();
		assertThat(plain.providerIds()).isNull();
	}

	@Test
	void membersWithoutProviderSettingsLeaveShelvesUnchanged() {
		// Acceptance criterion (#270): nothing configured → identical behavior,
		// no provider filter, no badges
		stubPopulatedWatchlist();
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, null, null),
			providerUser(8L, "US", List.of())));
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).isNotEmpty();
		assertThat(shelves).allSatisfy(shelf -> {
			assertThat(shelf.providerFiltered()).isFalse();
			assertThat(shelf.titles()).allSatisfy(t -> assertThat(t.providerIds()).isNull());
		});
	}

	// ── "What can we both watch" (#322) ───────────────────────
	//
	// The gate cases below assert the shelf's *absence*. They can't also prove the
	// builder bailed before drawing from the shared rng — that contract is checked
	// where it's observable, by the tuning harness: every fixture list is gated off,
	// and its baseline output stayed byte-identical when this stage was added.

	@Test
	void bothWatchShelfDiscoversAgainstTheProviderIntersection() {
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		// Netflix + Hulu, Netflix + Max → the only service they both have is Netflix (8)
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, "US", List.of(8, 9)),
			providerUser(8L, "US", List.of(8, 337))));
		when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"),
				isNull(), isNull(), eq("US"), any(), anyInt()))
			.thenAnswer(inv -> {
				List<Integer> providerIds = inv.getArgument(8);
				String pool = new HashSet<>(providerIds).equals(Set.of(8)) ? "bw-" : "gp-";
				return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate(pool + i)).toList();
			});

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).anySatisfy(shelf -> {
			assertThat(shelf.kind()).isEqualTo(SuggestionShelfResponse.ShelfKind.BOTH_WATCH);
			assertThat(shelf.reason()).isEqualTo("What you can both watch");
			assertThat(shelf.providerFiltered()).isTrue();
			assertThat(shelf.titles()).isNotEmpty();
			assertThat(shelf.titles()).allSatisfy(t -> assertThat(t.externalId()).startsWith("bw-"));
		});
		// The intersection, not the {8, 9, 337} union every other shelf discovers against
		verify(tmdbClient).discover(any(), any(), any(), eq(100), eq("popularity.desc"),
			isNull(), isNull(), eq("US"),
			argThat((List<Integer> ids) -> ids != null && new HashSet<>(ids).equals(Set.of(8))),
			anyInt());
	}

	@Test
	void bothWatchShelfIsSkippedWhenMembersShareNoService() {
		// Empty intersection is a real answer ("nothing you can both stream"), and the
		// honest way to give it is to not claim a shelf — not to widen the filter
		assertNoBothWatchShelf(
			providerUser(7L, "US", List.of(8, 9)),
			providerUser(8L, "US", List.of(337)));
	}

	@Test
	void bothWatchShelfIsSkippedWhenAMemberHasNoProvidersConfigured() {
		// The union quietly ignores an unconfigured member (they add nothing to it);
		// an intersection can't, or the shelf would promise a service nobody confirmed
		assertNoBothWatchShelf(
			providerUser(7L, "US", List.of(8)),
			providerUser(8L, null, null));
	}

	@Test
	void bothWatchShelfIsSkippedWhenMemberRegionsConflict() {
		assertNoBothWatchShelf(
			providerUser(7L, "US", List.of(8)),
			providerUser(8L, "GB", List.of(8)));
	}

	@Test
	void bothWatchShelfIsSkippedForAPersonalList() {
		// One member always "shares" every service with themselves — the shelf would
		// be a relabelled genre shelf, and its name would be a lie
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(7L));
		when(userRepository.findAllById(List.of(7L)))
			.thenReturn(List.of(providerUser(7L, "US", List.of(8))));
		lenient().when(tmdbClient.discover(any(), any(), any(), anyInt(), anyString(),
				any(), any(), any(), any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gp-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).noneMatch(s -> s.kind() == SuggestionShelfResponse.ShelfKind.BOTH_WATCH);
	}

	@Test
	void bothWatchTitlesAreBadgedWithOnlySharedServices() {
		// bw-1 is streamable on 8 (shared) and 9 (only one member has it). Every other
		// shelf would badge both; this one must not, or the tile contradicts its label
		stubPopulatedWatchlistWithGenres(
			Map.of("bw-1", providerRow("bw-1", Map.of("US", List.of(8, 9)))));
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(
			providerUser(7L, "US", List.of(8, 9)),
			providerUser(8L, "US", List.of(8, 337))));
		when(tmdbClient.discover(any(), any(), any(), eq(100), eq("popularity.desc"),
				isNull(), isNull(), eq("US"), any(), anyInt()))
			.thenAnswer(inv -> {
				List<Integer> providerIds = inv.getArgument(8);
				String pool = new HashSet<>(providerIds).equals(Set.of(8)) ? "bw-" : "gp-";
				return IntStream.rangeClosed(1, 12).mapToObj(i -> candidate(pool + i)).toList();
			});

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		TitleSearchResponse badged = shelves.stream()
			.filter(s -> s.kind() == SuggestionShelfResponse.ShelfKind.BOTH_WATCH)
			.flatMap(s -> s.titles().stream())
			.filter(t -> t.externalId().equals("bw-1"))
			.findFirst().orElseThrow();
		assertThat(badged.providerIds()).containsExactly(8);
	}

	@Test
	void titleThumbedDownByAnyMemberIsExcludedFromEveryShelf() {
		// Thumbs used to only steer the taste profile (#273), so a title a member had
		// explicitly rejected could still be suggested back to the list (#322)
		stubPopulatedWatchlist();
		when(titleRatingService.downRatedExternalIds(MEMBER_IDS)).thenReturn(List.of("rec-ext1-3"));
		when(tmdbClient.getRecommendations(any(), anyString(), anyInt()))
			.thenAnswer(inv -> candidatesFor(inv.getArgument(1)));

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).isNotEmpty();
		assertThat(shelves)
			.flatExtracting(SuggestionShelfResponse::titles)
			.extracting(TitleSearchResponse::externalId)
			.contains("rec-ext1-1")
			.doesNotContain("rec-ext1-3");
	}

	private void assertNoBothWatchShelf(User... members) {
		stubPopulatedWatchlistWithGenres();
		when(watchlistMemberRepository.findUserIdsByWatchlistId(WATCHLIST_ID)).thenReturn(MEMBER_IDS);
		when(userRepository.findAllById(MEMBER_IDS)).thenReturn(List.of(members));
		lenient().when(tmdbClient.discover(any(), any(), any(), anyInt(), anyString(),
				any(), any(), any(), any(), anyInt()))
			.thenAnswer(inv -> IntStream.rangeClosed(1, 12).mapToObj(i -> candidate("gp-" + i)).toList());

		List<SuggestionShelfResponse> shelves = serviceAt(DAY_1).topPicks(WATCHLIST_ID);

		assertThat(shelves).noneMatch(s -> s.kind() == SuggestionShelfResponse.ShelfKind.BOTH_WATCH);
	}

	private User providerUser(long id, String watchRegion, List<Integer> watchProviderIds) {
		User u = new User();
		u.setId(id);
		u.setWatchRegion(watchRegion);
		u.setWatchProviderIds(watchProviderIds);
		return u;
	}

	private TmdbTitleCache providerRow(String tmdbId, Map<String, List<Integer>> watchProviders) {
		TmdbTitleCache c = cacheRow(tmdbId, null, null);
		c.setWatchProviders(watchProviders);
		return c;
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
		return cacheRow(tmdbId, genreIds, keywordIds, null);
	}

	private TmdbTitleCache cacheRow(String tmdbId, List<Integer> genreIds, List<Integer> keywordIds, Integer voteCount) {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(tmdbId);
		c.setGenreIds(genreIds);
		c.setKeywordIds(keywordIds);
		c.setVoteCount(voteCount);
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
