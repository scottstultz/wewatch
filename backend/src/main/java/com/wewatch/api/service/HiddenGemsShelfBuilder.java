package com.wewatch.api.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

// "Hidden gems for you" (#376): well-rated titles in the user's genres that
// they are unlikely to have heard of — taste-ranked, then gated to those under
// an absolute TMDB popularity ceiling.
//
// Takes over the HIDDEN_GEMS kind that ExplorationShelfBuilder gave up in #375,
// and is three real upgrades on it rather than a rename: it is always-on for
// anyone with a genre profile instead of winning a 2-of-5 daily lottery; the
// obscurity test is TMDB's own popularity value instead of a discover
// page-depth proxy; and the page is ranked by taste-profile fit instead of
// passing raw discover order to the filler.
class HiddenGemsShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(HiddenGemsShelfBuilder.class);

	// High enough to keep vote_average.desc from surfacing barely-rated noise,
	// low enough to reach below the popularity head. Carried over from the
	// removed exploration kind, which shared the sort order and the problem.
	private static final int VOTE_COUNT_GTE = 200;

	private final TmdbClient tmdbClient;
	private final CandidateScorer scorer;
	private final ShelfFiller filler;
	private final SuggestionTuningProperties tuning;

	HiddenGemsShelfBuilder(TmdbClient tmdbClient, CandidateScorer scorer, ShelfFiller filler,
			SuggestionTuningProperties tuning) {
		this.tmdbClient = tmdbClient;
		this.scorer = scorer;
		this.filler = filler;
		this.tuning = tuning;
	}

	SuggestionShelfResponse build(SuggestionContext ctx) {
		List<Integer> topGenres = ctx.profile().dominantGenres();
		// Bail before the page draw, not after: a stage that consumes from the
		// shared day-seeded rng only sometimes would move every downstream shelf
		// for the users it skips. See SuggestionContext on why draw order is behavior.
		if (topGenres.isEmpty()) return null;

		TitleType type = ctx.profile().dominantType();
		ProviderContext providers = ctx.providers();

		// The standard 1..MAX_FETCH_PAGE band, unlike the deep 4..18 band the
		// removed exploration kind drew from (#375). That band was itself the
		// obscurity filter — a proxy for "past the pages everyone sees". This
		// shelf gates on the real popularity value, so the proxy is redundant and
		// the shallow pages become usable stock again.
		int page = 1 + ctx.rng().nextInt(DiscoverPolicy.MAX_FETCH_PAGE);
		List<TitleSearchResponse> candidates;
		try {
			candidates = TmdbPaging.fetchPageWithFallback(p -> tmdbClient.discover(
				type, topGenres, List.of(), VOTE_COUNT_GTE,
				DiscoverPolicy.SORT_VOTE_AVERAGE, null, null,
				providers.region(), providers.providerIdList(), p), page);
		} catch (TmdbApiException e) {
			log.warn("Hidden gems discover failed for {} genres {}: {}", type, topGenres, e.getMessage());
			return null;
		}

		// Rank first, gate second — deliberately the more expensive order.
		// CandidateScorer.jitterByCandidate draws once per distinct candidate, so
		// filtering first would make the number of rng draws a function of the
		// ceiling *value*: re-tuning the ceiling would then shift every downstream
		// exploration shelf for every user. No other tuning knob has that blast
		// radius — the rest change scores, not draw counts. Ranking the full page
		// pins the draw count to page size and keeps the ceiling a free knob.
		List<TitleSearchResponse> ranked = scorer.rankByTasteProfile(candidates, ctx);
		List<TitleSearchResponse> gated = ranked.stream()
			.filter(this::isObscure)
			.toList();

		// No genre diversification (#265): this feed is filtered to topGenres, so
		// nearly every candidate shares a genre and the cluster cap would chop the
		// page well below a full shelf. Same exemption the removed kind had.
		List<TitleSearchResponse> shelf = filler.fill(gated, ctx.seen(), ctx.recencyWeights(), false);
		return shelf.size() >= ShelfFiller.MIN_SHELF_SIZE
			? new SuggestionShelfResponse("Hidden gems for you", shelf,
				SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS, providers.enabled())
			: null;
	}

	// Unknown popularity is excluded, not admitted. Only TMDB-sourced feeds
	// populate the field (#374), so a null here means the candidate reached this
	// shelf by a path that can't answer the one question the shelf asks — and
	// admitting it would let the most popular titles in through the gap.
	private boolean isObscure(TitleSearchResponse candidate) {
		Double popularity = candidate.popularity();
		return popularity != null && popularity < tuning.getHiddenGemPopularityCeiling();
	}
}
