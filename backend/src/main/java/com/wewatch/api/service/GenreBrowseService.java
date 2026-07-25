package com.wewatch.api.service;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.tmdb.TmdbClient;

/**
 * Browsing TMDB by genre, ranked against a watchlist's taste profile (#384).
 *
 * <p>The question Discover could not answer before this: "show me sci-fi we'd like". The shelves
 * decide what to serve; this lets the user ask. One TMDB discover page per call, AND-ed across the
 * selected genres, with the titles already on the list — or dismissed, or thumbs-downed — taken
 * out, and the rest reordered by the same taste profile that powers the shelves.
 *
 * <p><strong>⚠️ This is deliberately not a stage in {@code SuggestionService.topPicks}.</strong>
 * The pipeline's stages share one dedup set and one day-seeded {@link java.util.Random}, so stage
 * order is behavior — adding a draw or reordering silently moves every user's shelves. Browse calls
 * {@link SuggestionService#loadContext} to build its <em>own</em> {@link SuggestionContext} per
 * request, so its rng is a separate stream and the pipeline is untouched. The tuning harness output
 * is byte-identical across this whole feature for that reason.
 *
 * <p>Three deliberate departures from how the shelves work:
 *
 * <ul>
 * <li><strong>Badges, no provider filter.</strong> Two AND-ed genres already narrow hard; stacking
 * TMDB's {@code with_watch_providers} on top is how browse ends up empty and reads as broken. The
 * taste ranking still <em>boosts</em> streamable titles ({@code CandidateScorer}'s cache signals) —
 * a boost is not a filter.
 * <li><strong>No {@link ShelfFiller}.</strong> It applies the impression recency penalty, genre
 * diversification and shelf-size floors, and it mutates the shared {@code seen} set. All of that is
 * shelf shaping; a browse grid is a paged answer to a question the user just asked, and yesterday's
 * impressions have no claim on it.
 * <li><strong>Ranking is within a page.</strong> TMDB paginates by popularity, so page 2 holds less
 * popular titles than page 1 no matter how they score here. "Load more" appends a second ranked
 * page rather than re-ranking a bigger pool — the alternative is fetching every page up front.
 * </ul>
 */
@Service
public class GenreBrowseService {

	private final SuggestionService suggestionService;
	private final TmdbClient tmdbClient;
	private final CandidateScorer scorer;
	private final ProviderContextResolver providerResolver;

	// Keyed on (watchlistId, type, sorted genre ids, page) — every input that
	// changes the answer. Modelled on RecommendationService (#358): one page per
	// key, so the key space is bounded by the depth cap.
	//
	// Nothing evicts this on a watchlist change, so adding a title from a browse
	// tile can leave it in a cached page for up to the TTL. Deliberate: the client
	// already shows the tile as added (its own cardStatus), and the alternative is
	// wiring browse into every mutation path for a cosmetic gain.
	private final Cache<String, List<TitleSearchResponse>> cache;

	public GenreBrowseService(
		SuggestionService suggestionService,
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository tmdbTitleCacheRepository,
		UserRepository userRepository,
		SuggestionTuningProperties tuning,
		@Value("${suggestions.browse.ttl-minutes:30}") long ttlMinutes,
		@Value("${suggestions.browse.max-size:200}") long maxSize
	) {
		this.suggestionService = suggestionService;
		this.tmdbClient = tmdbClient;
		// Constructed rather than injected, the same wiring choice SuggestionService
		// makes for its own collaborators (#319): both are stateless helpers whose
		// per-request state travels in the SuggestionContext.
		this.scorer = new CandidateScorer(tmdbTitleCacheRepository, tuning);
		this.providerResolver = new ProviderContextResolver(userRepository, tmdbTitleCacheRepository);
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofMinutes(ttlMinutes))
			.maximumSize(maxSize)
			.build();
	}

	/**
	 * One page of titles carrying <em>every</em> given genre, ranked for this watchlist.
	 *
	 * @throws IllegalArgumentException when no genres are given or the page is outside
	 *     1..{@link DiscoverPolicy#MAX_FETCH_PAGE} (→ 400)
	 */
	public List<TitleSearchResponse> browse(Long watchlistId, TitleType type, List<Integer> genreIds, int page) {
		if (genreIds == null || genreIds.isEmpty()) {
			throw new IllegalArgumentException("At least one genre is required");
		}
		if (page < 1 || page > DiscoverPolicy.MAX_FETCH_PAGE) {
			throw new IllegalArgumentException(
				"page must be between 1 and " + DiscoverPolicy.MAX_FETCH_PAGE);
		}

		// Sorted so the same selection ticked in a different order is one cache entry,
		// and distinct so a repeated id can't split it either.
		List<Integer> genres = genreIds.stream().distinct().sorted().toList();
		String key = watchlistId + ":" + type + ":" + genres + ":" + page;
		// A TmdbApiException from the loader propagates (→ 502) and Caffeine stores
		// nothing, so an outage isn't poisoned into the cache.
		return cache.get(key, k -> fetch(watchlistId, type, genres, page));
	}

	private List<TitleSearchResponse> fetch(Long watchlistId, TitleType type, List<Integer> genres, int page) {
		List<TitleSearchResponse> candidates = tmdbClient.discover(
			type, genres, TmdbClient.GENRE_JOIN_AND, List.of(), DiscoverPolicy.VOTE_COUNT_GTE,
			DiscoverPolicy.SORT_POPULARITY, null, null,
			// Null region/providers: badge, don't filter (see the class comment)
			null, null, page);

		SuggestionContext ctx = suggestionService.loadContext(watchlistId);
		// An empty watchlist has no taste profile and nothing to exclude, so browse
		// serves TMDB's own popularity order. A new user asking for sci-fi should get
		// sci-fi, not an empty grid because the ranking had nothing to say.
		if (ctx == null) return candidates;

		// Excluded before ranking, so an owned or rejected title doesn't take up a
		// slot in the page. Safe to order it this way here — unlike the #376 hidden-gems
		// gate, whose position mattered because it shared the pipeline's rng stream and
		// so could shift every later shelf. This rng is per-request and read by nothing
		// else, so the draw count is nobody's business but this page's.
		List<TitleSearchResponse> eligible = candidates.stream()
			.filter(c -> !ctx.seen().contains(c.externalId()))
			.toList();

		List<TitleSearchResponse> ranked = scorer.rankByTasteProfile(eligible, ctx);
		return providerResolver.badge(ranked, ctx.providers());
	}
}
