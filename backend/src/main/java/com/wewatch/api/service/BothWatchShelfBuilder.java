package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

// "What you can both watch" (#322): the shelf a household actually opens WeWatch
// to get. Every other provider-aware shelf answers to the *union* of the members'
// services — "one of you can stream this" — which is the wrong question for a
// shared list. This one answers to the intersection.
//
// TMDB's with_watch_providers is an OR over the ids you pass, so handing it the
// intersected set is exactly "streamable on a service all of you have". The
// filtering is the TMDB feed's job, not ours: no candidate reaches the shelf
// unless it passed the filter, so the shelf is correct even where our own
// per-title provider cache is cold.
//
// Mixed TV and movie in one shelf, deliberately: the question is "what do we
// watch tonight", and the answer shouldn't have to pre-commit to a medium.
class BothWatchShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(BothWatchShelfBuilder.class);

	private final TmdbClient tmdbClient;
	private final CandidateScorer scorer;
	private final ShelfFiller filler;

	BothWatchShelfBuilder(TmdbClient tmdbClient, CandidateScorer scorer, ShelfFiller filler) {
		this.tmdbClient = tmdbClient;
		this.scorer = scorer;
		this.filler = filler;
	}

	SuggestionShelfResponse build(SuggestionContext ctx) {
		ProviderContext shared = ctx.sharedProviders();
		// Bail before touching the rng: a personal list, an unconfigured member, or
		// members who share no service must draw nothing, or every downstream shelf
		// shifts for them (see SuggestionContext).
		if (!shared.enabled()) return null;

		List<TitleSearchResponse> candidates = new ArrayList<>();
		for (TitleType type : List.of(TitleType.TV, TitleType.MOVIE)) {
			candidates.addAll(discoverFor(ctx, type, shared));
		}
		if (candidates.isEmpty()) return null;

		List<TitleSearchResponse> ranked = scorer.rankByTasteProfile(candidates, ctx);
		// Diversify: this pool spans two media and every genre the list leans on, so
		// the cluster cap is what keeps it from collapsing into one of them.
		List<TitleSearchResponse> shelf = filler.fill(ranked, ctx.seen(), ctx.recencyWeights(), true);
		if (shelf.size() < ShelfFiller.MIN_SHELF_SIZE) return null;

		return new SuggestionShelfResponse(label(ctx), shelf,
			SuggestionShelfResponse.ShelfKind.BOTH_WATCH, true);
	}

	private List<TitleSearchResponse> discoverFor(SuggestionContext ctx, TitleType type, ProviderContext shared) {
		List<Integer> topGenres = ctx.profile().topGenres(type);
		// No genre signal for this medium — skip it rather than send TMDB an empty
		// with_genres, and skip it *before* the page draw (the bail-before-drawing rule
		// again, since a list can lean on one medium and not the other).
		if (topGenres.isEmpty()) return List.of();

		int page = 1 + ctx.rng().nextInt(DiscoverPolicy.MAX_FETCH_PAGE);
		try {
			return TmdbPaging.fetchPageWithFallback(
				p -> tmdbClient.discover(type, topGenres, List.of(), DiscoverPolicy.VOTE_COUNT_GTE,
					DiscoverPolicy.SORT_POPULARITY, null, null,
					shared.region(), shared.providerIdList(), p), page);
		} catch (TmdbApiException e) {
			log.warn("Shared-provider discover failed for {} genres {}: {}", type, topGenres, e.getMessage());
			return List.of();
		}
	}

	private String label(SuggestionContext ctx) {
		return ctx.memberUserIds().size() == 2 ? "What you can both watch" : "What you can all watch";
	}
}
