package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.tmdb.TmdbClient;

// "More like your shows" / "More like your movies" (#232): one TMDB discover
// shelf per medium, filtered to that medium's top genres and keywords. Built
// only for a medium the list actually leans on — under GENRE_GROUP_THRESHOLD
// entries there isn't enough signal to call it a profile.
class GenreShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(GenreShelfBuilder.class);

	private static final int GENRE_GROUP_THRESHOLD = 3;

	private final TmdbClient tmdbClient;
	private final ShelfFiller filler;

	GenreShelfBuilder(TmdbClient tmdbClient, ShelfFiller filler) {
		this.tmdbClient = tmdbClient;
		this.filler = filler;
	}

	List<SuggestionShelfResponse> build(SuggestionContext ctx) {
		List<SuggestionShelfResponse> shelves = new ArrayList<>();
		for (TitleType type : List.of(TitleType.TV, TitleType.MOVIE)) {
			SuggestionShelfResponse shelf = buildFor(ctx, type);
			if (shelf != null) shelves.add(shelf);
		}
		return shelves;
	}

	private SuggestionShelfResponse buildFor(SuggestionContext ctx, TitleType type) {
		List<WatchlistEntry> group = ctx.entries().stream()
			.filter(e -> {
				Title t = ctx.titlesById().get(e.getTitleId());
				return t != null && t.getType() == type;
			})
			.toList();

		if (group.size() < GENRE_GROUP_THRESHOLD) return null;

		List<Integer> topGenres = ctx.profile().topGenres(type);
		if (topGenres.isEmpty()) return null;

		List<Integer> typeKeywords = typeScopedKeywords(ctx, group);
		String label = type == TitleType.TV ? "More like your shows" : "More like your movies";
		ProviderContext providers = ctx.providers();
		int discoverPage = 1 + ctx.rng().nextInt(DiscoverPolicy.MAX_FETCH_PAGE);
		try {
			// Discover takes TMDB's provider filter directly (#270): with a
			// provider context, everything on this shelf is streamable on the
			// members' services
			List<TitleSearchResponse> discovered = TmdbPaging.fetchPageWithFallback(
				p -> tmdbClient.discover(type, topGenres, typeKeywords, DiscoverPolicy.VOTE_COUNT_GTE,
					DiscoverPolicy.SORT_POPULARITY, null, null,
					providers.region(), providers.providerIdList(), p), discoverPage);
			// No genre diversification (#265): this feed is filtered to topGenres
			List<TitleSearchResponse> shelf = filler.fill(discovered, ctx.seen(), ctx.recencyWeights(), false);
			if (shelf.size() >= ShelfFiller.MIN_SHELF_SIZE) {
				return new SuggestionShelfResponse(label, shelf,
					SuggestionShelfResponse.ShelfKind.GENRE_PROFILE, providers.enabled());
			}
		} catch (TmdbApiException e) {
			log.warn("Discover failed for {} genres {}: {}", type, topGenres, e.getMessage());
		}
		return null;
	}

	// Type-scoped keywords to avoid cross-namespace contamination. Deliberately a
	// plain per-title count rather than the rating-weighted, recency-decayed
	// keyword affinities on the profile: this is a discover filter narrowing one
	// medium's shelf, not a statement about taste.
	private List<Integer> typeScopedKeywords(SuggestionContext ctx, List<WatchlistEntry> group) {
		return group.stream()
			.map(e -> ctx.titlesById().get(e.getTitleId()))
			.filter(Objects::nonNull)
			.map(t -> ctx.cacheByTmdbId().get(TmdbCacheKey.of(t)))
			.filter(Objects::nonNull)
			.flatMap(c -> c.getKeywordIds() != null ? c.getKeywordIds().stream() : Stream.<Integer>empty())
			.collect(Collectors.groupingBy(k -> k, Collectors.summingInt(k -> 1)))
			.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(TasteProfile.MAX_KEYWORDS)
			.map(Map.Entry::getKey)
			.toList();
	}
}
