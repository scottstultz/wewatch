package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.tmdb.TmdbClient;

// Genre browsing (#384). The service leans on SuggestionService.loadContext for its
// inputs, so that is mocked and the contexts are built by hand here — which also
// keeps every test honest about which single input it is exercising.
@ExtendWith(MockitoExtension.class)
class GenreBrowseServiceTest {

	@Mock private SuggestionService suggestionService;
	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository tmdbTitleCacheRepository;
	@Mock private UserRepository userRepository;

	private static final long WATCHLIST_ID = 42L;
	private static final List<Long> MEMBER_IDS = List.of(7L);
	private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);
	// Romance + Comedy — the rom-com case the issue is written around
	private static final List<Integer> ROM_COM = List.of(10749, 35);

	private GenreBrowseService service() {
		return new GenreBrowseService(suggestionService, tmdbClient, tmdbTitleCacheRepository,
			userRepository, new SuggestionTuningProperties(), 30L, 200L);
	}

	@Test
	void browseJoinsGenresWithAndAndServesTheRankedPage() {
		stubContext(context(Set.of()));
		when(tmdbClient.discover(eq(TitleType.MOVIE), eq(List.of(35, 10749)), eq(","), eq(List.of()),
				anyInt(), anyString(), isNull(), isNull(), isNull(), isNull(), eq(1)))
			.thenReturn(List.of(candidate("m1"), candidate("m2")));

		List<TitleSearchResponse> page = service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		assertThat(page).extracting(TitleSearchResponse::externalId).containsExactlyInAnyOrder("m1", "m2");
		// The whole point: "," is AND (titles carrying *both*), where every shelf
		// builder sends "|". A pipe here answers a different question entirely.
		verify(tmdbClient).discover(eq(TitleType.MOVIE), eq(List.of(35, 10749)), eq(","), eq(List.of()),
			anyInt(), anyString(), isNull(), isNull(), isNull(), isNull(), eq(1));
	}

	@Test
	void browseSendsNoProviderFilter() {
		stubContext(context(Set.of(), providerContext()));
		stubDiscover(List.of(candidate("m1")));

		service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		// Region and provider ids stay null even with a provider context: browse
		// badges but never filters, or two AND-ed genres plus a provider filter
		// leave an empty grid that reads as broken.
		verify(tmdbClient).discover(any(), any(), anyString(), any(), anyInt(), anyString(),
			isNull(), isNull(), isNull(), isNull(), anyInt());
	}

	@Test
	void browseExcludesOwnedDismissedAndDownRatedTitles() {
		// loadContext seeds `seen` with all three: owned external ids, dismissals
		// (#268), and thumbs-downs (#322)
		stubContext(context(Set.of("owned", "dismissed", "down-rated")));
		stubDiscover(List.of(candidate("owned"), candidate("dismissed"),
			candidate("down-rated"), candidate("fresh")));

		List<TitleSearchResponse> page = service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		assertThat(page).extracting(TitleSearchResponse::externalId).containsExactly("fresh");
	}

	@Test
	void browseRanksByTasteProfile() {
		// Genre affinity only, no jitter, so the expected order is exact: the
		// candidate carrying the profiled genre outranks the one that doesn't.
		SuggestionTuningProperties noJitter = new SuggestionTuningProperties();
		noJitter.setScoreJitterFloor(0.0);
		noJitter.setScoreJitterFraction(0.0);
		stubContext(context(Set.of(), ProviderContext.DISABLED, Map.of(35, 5.0)));
		stubDiscover(List.of(scored("unloved", List.of(10749)), scored("loved", List.of(35))));

		List<TitleSearchResponse> page = new GenreBrowseService(suggestionService, tmdbClient,
			tmdbTitleCacheRepository, userRepository, noJitter, 30L, 200L)
			.browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		assertThat(page).extracting(TitleSearchResponse::externalId).containsExactly("loved", "unloved");
	}

	@Test
	void browseAttachesProviderBadgesWithoutFiltering() {
		stubContext(context(Set.of(), providerContext()));
		stubDiscover(List.of(candidate("streamable"), candidate("nowhere")));
		// No userRepository stub: the provider context arrives already resolved on the
		// SuggestionContext, so badging is a title-cache read and nothing else. Mockito's
		// strict stubbing is what proves that.
		when(tmdbTitleCacheRepository.findAllById(any())).thenReturn(List.of(
			providerRow("streamable", Map.of("US", List.of(8, 350)))));

		List<TitleSearchResponse> page = service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		// Both titles survive — the one the members can stream is badged, the other
		// stays unknown rather than being dropped
		assertThat(page).extracting(TitleSearchResponse::externalId)
			.containsExactlyInAnyOrder("streamable", "nowhere");
		assertThat(page).filteredOn(t -> t.externalId().equals("streamable"))
			.singleElement()
			.extracting(TitleSearchResponse::providerIds)
			.isEqualTo(List.of(8));
		assertThat(page).filteredOn(t -> t.externalId().equals("nowhere"))
			.singleElement()
			.extracting(TitleSearchResponse::providerIds)
			.isNull();
	}

	@Test
	void browseServesAnEmptyWatchlistTmdbOrderUnranked() {
		// loadContext returns null for an empty list. A new user asking for sci-fi
		// should get sci-fi, not an empty grid because the ranking had nothing to say.
		when(suggestionService.loadContext(WATCHLIST_ID)).thenReturn(null);
		stubDiscover(List.of(candidate("first"), candidate("second"), candidate("third")));

		List<TitleSearchResponse> page = service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		assertThat(page).extracting(TitleSearchResponse::externalId)
			.containsExactly("first", "second", "third");
		verifyNoInteractions(tmdbTitleCacheRepository, userRepository);
	}

	@Test
	void browseCachesPerWatchlistTypeGenresAndPage() {
		stubContext(context(Set.of()));
		stubDiscover(List.of(candidate("m1")));
		GenreBrowseService service = service();

		service.browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);
		service.browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);
		// Same ids ticked in the other order is the same question, so it must hit the
		// same entry — the key sorts them
		service.browse(WATCHLIST_ID, TitleType.MOVIE, List.of(35, 10749), 1);

		verify(tmdbClient, times(1)).discover(any(), any(), anyString(), any(), anyInt(),
			anyString(), any(), any(), any(), any(), anyInt());
	}

	@Test
	void browseTreatsTypeGenresAndPageAsDistinctCacheEntries() {
		stubContext(context(Set.of()));
		stubDiscover(List.of(candidate("m1")));
		GenreBrowseService service = service();

		service.browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);
		service.browse(WATCHLIST_ID, TitleType.TV, ROM_COM, 1);
		service.browse(WATCHLIST_ID, TitleType.MOVIE, List.of(35), 1);
		service.browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 2);
		service.browse(99L, TitleType.MOVIE, ROM_COM, 1);

		verify(tmdbClient, times(5)).discover(any(), any(), anyString(), any(), anyInt(),
			anyString(), any(), any(), any(), any(), anyInt());
	}

	@Test
	void browseRequestsTheGivenPage() {
		stubContext(context(Set.of()));
		stubDiscover(List.of(candidate("m1")));

		service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, DiscoverPolicy.MAX_FETCH_PAGE);

		verify(tmdbClient).discover(any(), any(), anyString(), any(), anyInt(), anyString(),
			any(), any(), any(), any(), eq(DiscoverPolicy.MAX_FETCH_PAGE));
	}

	@Test
	void browseRejectsAPageBeyondTheDepthCap() {
		assertThatThrownBy(() ->
			service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, DiscoverPolicy.MAX_FETCH_PAGE + 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("page");

		verify(tmdbClient, never()).discover(any(), any(), anyString(), any(), anyInt(),
			anyString(), any(), any(), any(), any(), anyInt());
	}

	@Test
	void browseRejectsAPageBelowOne() {
		assertThatThrownBy(() -> service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void browseRejectsAnEmptyGenreSelection() {
		// Browse *is* the genre question — with none selected there is nothing to ask,
		// and the client renders shelves instead of calling this at all
		assertThatThrownBy(() -> service().browse(WATCHLIST_ID, TitleType.MOVIE, List.of(), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("genre");
	}

	@Test
	void browseDoesNotTouchTheSuggestionPipeline() {
		stubContext(context(Set.of()));
		stubDiscover(List.of(candidate("m1")));

		service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1);

		// The context is all browse wants from SuggestionService. topPicks would run
		// the whole stage order and draw from the shared rng — the one thing this
		// feature must never do.
		verify(suggestionService).loadContext(WATCHLIST_ID);
		verify(suggestionService, never()).topPicks(any());
	}

	@Test
	void browseFillsAWholePageWhenNothingIsExcluded() {
		stubContext(context(Set.of()));
		stubDiscover(IntStream.rangeClosed(1, 20).mapToObj(i -> candidate("m" + i)).toList());

		assertThat(service().browse(WATCHLIST_ID, TitleType.MOVIE, ROM_COM, 1)).hasSize(20);
	}

	private void stubContext(SuggestionContext ctx) {
		when(suggestionService.loadContext(WATCHLIST_ID)).thenReturn(ctx);
	}

	private void stubDiscover(List<TitleSearchResponse> results) {
		when(tmdbClient.discover(any(), any(), anyString(), any(), anyInt(), anyString(),
				any(), any(), any(), any(), anyInt()))
			.thenReturn(results);
	}

	private SuggestionContext context(Set<String> seen) {
		return context(seen, ProviderContext.DISABLED);
	}

	private SuggestionContext context(Set<String> seen, ProviderContext providers) {
		return context(seen, providers, Map.of());
	}

	private SuggestionContext context(Set<String> seen, ProviderContext providers,
			Map<Integer, Double> genreProfile) {
		TasteProfile profile = TasteProfile.of(genreProfile, List.of(), List.of(),
			Map.of(), TitleType.MOVIE);
		return new SuggestionContext(TODAY, MEMBER_IDS, List.of(), Map.of(), Map.of(), profile,
			providers, ProviderContext.DISABLED, new HashSet<>(seen), Map.of(), new Random(1L));
	}

	private ProviderContext providerContext() {
		return new ProviderContext("US", Set.of(8, 9));
	}

	private TmdbTitleCache providerRow(String tmdbId, Map<String, List<Integer>> watchProviders) {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(tmdbId);
		c.setWatchProviders(watchProviders);
		return c;
	}

	private TitleSearchResponse candidate(String externalId) {
		return scored(externalId, List.of());
	}

	private TitleSearchResponse scored(String externalId, List<Integer> genreIds) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.MOVIE, "Title " + externalId,
			null, null, null, genreIds);
	}
}
