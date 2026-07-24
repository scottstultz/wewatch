package com.wewatch.api.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

// Exploration shelves (#235) trade similarity for discovery: recent releases,
// this week's trending, a recurring person (#269), and a favorite theme (#271)
// — still deduped and recency-demoted like every other shelf.
//
// Each kind costs TMDB calls, so only a rotating subset appears on a given day:
// the kinds are tried in a daily-shuffled order and the first MAX_EXPLORATION_SHELVES
// that actually fill are kept. A kind that can't fill (no genre data, empty TMDB
// response) yields its slot to the next one. Rotation is itself a freshness lever.
class ExplorationShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(ExplorationShelfBuilder.class);

	private static final int MAX_EXPLORATION_SHELVES = 2;
	// Trending/week thins into obscurity fast, so keep its draw shallow.
	private static final int MAX_TRENDING_FETCH_PAGE = 3;
	// A single keyword's catalog is far thinner than a genre's — niche themes run
	// out within a few pages — so the keyword shelf (#271) draws shallow like the
	// seed feeds; its main rotation lever is which keyword the day picks anyway.
	private static final int MAX_KEYWORD_FETCH_PAGE = 3;
	private static final int NEW_RELEASE_WINDOW_DAYS = 60;
	// Recent releases haven't had time to accumulate votes, so the floor is far
	// below the genre-profile discover floor of 100
	private static final int NEW_RELEASE_VOTE_COUNT_GTE = 20;
	// A filmography includes shorts and bit parts; a modest floor keeps the
	// person shelf to titles a general audience has actually seen
	private static final int PERSON_SHELF_VOTE_COUNT_GTE = 50;

	private final TmdbClient tmdbClient;
	private final CandidateScorer scorer;
	private final ShelfFiller filler;

	ExplorationShelfBuilder(TmdbClient tmdbClient, CandidateScorer scorer, ShelfFiller filler) {
		this.tmdbClient = tmdbClient;
		this.scorer = scorer;
		this.filler = filler;
	}

	List<SuggestionShelfResponse> build(SuggestionContext ctx) {
		List<SuggestionShelfResponse.ShelfKind> order = new ArrayList<>(List.of(
			SuggestionShelfResponse.ShelfKind.NEW_RELEASES,
			SuggestionShelfResponse.ShelfKind.TRENDING,
			SuggestionShelfResponse.ShelfKind.PERSON,
			SuggestionShelfResponse.ShelfKind.KEYWORD));
		Collections.shuffle(order, ctx.rng());

		List<SuggestionShelfResponse> shelves = new ArrayList<>();
		for (SuggestionShelfResponse.ShelfKind kind : order) {
			if (shelves.size() >= MAX_EXPLORATION_SHELVES) break;
			SuggestionShelfResponse shelf = buildOne(ctx, kind);
			if (shelf != null) shelves.add(shelf);
		}
		return shelves;
	}

	// Returns null when the kind can't produce a shelf so the caller can try the
	// next kind. A kind that bails out early (no genres, no recurring person, no
	// named keyword) must bail out *before* drawing from the rng — see
	// SuggestionContext on why draw order is behavior.
	private SuggestionShelfResponse buildOne(SuggestionContext ctx, SuggestionShelfResponse.ShelfKind kind) {
		TitleType type = ctx.profile().dominantType();
		List<Integer> topGenres = ctx.profile().dominantGenres();
		ProviderContext providers = ctx.providers();

		// Discover-backed kinds take TMDB's provider filter (#270); trending and
		// person feeds don't support it and stay unfiltered
		boolean providerFiltered = providers.enabled()
			&& (kind == SuggestionShelfResponse.ShelfKind.NEW_RELEASES
				|| kind == SuggestionShelfResponse.ShelfKind.KEYWORD);

		// Page draw is per-kind (#249): discover-backed kinds go deeper, trending
		// stays shallow, PERSON spends its draw picking the person instead of a page,
		// and KEYWORD draws a keyword plus a shallow page. A fixed draw count per
		// kind either way, so daily reproducibility (#231/#248) is preserved.
		List<TitleSearchResponse> candidates;
		String label;
		try {
			switch (kind) {
				case NEW_RELEASES -> {
					if (topGenres.isEmpty()) return null;
					int page = 1 + ctx.rng().nextInt(DiscoverPolicy.MAX_FETCH_PAGE);
					LocalDate today = ctx.today();
					candidates = TmdbPaging.fetchPageWithFallback(p -> tmdbClient.discover(
						type, topGenres, List.of(), NEW_RELEASE_VOTE_COUNT_GTE,
						DiscoverPolicy.SORT_POPULARITY, today.minusDays(NEW_RELEASE_WINDOW_DAYS), today,
						providers.region(), providers.providerIdList(), p), page);
					label = "New in your genres";
				}
				case PERSON -> {
					// Movie-only (TMDB's TV discover has no people filter) and always
					// page 1 — a filmography is shallow; the daily rotation comes from
					// which recurring person the rng draws, not from page depth (#269)
					List<PersonAffinity> people = ctx.profile().personAffinities();
					if (people.isEmpty()) return null;
					PersonAffinity person = people.get(ctx.rng().nextInt(people.size()));
					candidates = tmdbClient.discoverByPerson(person.id(), PERSON_SHELF_VOTE_COUNT_GTE, 1);
					label = person.director()
						? "Directed by " + person.name()
						: "More with " + person.name();
				}
				case KEYWORD -> {
					// Only keywords with a cached display name qualify — the label
					// is the whole point (#271); rows cached before V20 contribute
					// scoring ids but no shelf until the TTL refresh backfills names
					List<KeywordAffinity> named = ctx.profile().keywordAffinities().stream()
						.filter(k -> k.name() != null && !k.name().isBlank())
						.toList();
					if (named.isEmpty()) return null;
					KeywordAffinity keyword = named.get(ctx.rng().nextInt(named.size()));
					int page = 1 + ctx.rng().nextInt(MAX_KEYWORD_FETCH_PAGE);
					candidates = TmdbPaging.fetchPageWithFallback(p -> tmdbClient.discoverByKeyword(
						type, keyword.id(), DiscoverPolicy.VOTE_COUNT_GTE,
						providers.region(), providers.providerIdList(), p), page);
					label = keywordLabel(keyword.name(), type);
				}
				case TRENDING -> {
					int page = 1 + ctx.rng().nextInt(MAX_TRENDING_FETCH_PAGE);
					candidates = rankedTrending(ctx, type, page);
					label = "Trending now";
				}
				default -> {
					return null;
				}
			}
		} catch (TmdbApiException e) {
			log.warn("Exploration shelf {} failed for {}: {}", kind, type, e.getMessage());
			return null;
		}

		// Only trending carries a real genre mix worth diversifying; the discover-backed
		// kinds are genre-filtered by construction and are exempt from the cap (#265).
		// PERSON and KEYWORD are exempt too: the person or theme is the shelf's
		// coherence axis, and a same-genre run is the point, not a lack of variety.
		boolean diversify = kind == SuggestionShelfResponse.ShelfKind.TRENDING;
		List<TitleSearchResponse> shelf = filler.fill(candidates, ctx.seen(), ctx.recencyWeights(), diversify);
		return shelf.size() >= ShelfFiller.MIN_SHELF_SIZE
			? new SuggestionShelfResponse(label, shelf, kind, providerFiltered)
			: null;
	}

	// Rank the raw popularity feed by taste-profile affinity plus day-seeded
	// score-proportional jitter (#248/#267) so the order rotates daily beyond
	// ties; with no genre profile the floor amplitude degrades the sort to
	// stable-per-day random.
	private List<TitleSearchResponse> rankedTrending(SuggestionContext ctx, TitleType type, int page) {
		Map<Integer, Double> genreProfile = ctx.profile().genreProfile();
		List<TitleSearchResponse> trending = new ArrayList<>(
			TmdbPaging.fetchPageWithFallback(p -> tmdbClient.getTrending(type, p), page));
		Map<String, Double> jitter = scorer.jitterByCandidate(
			trending, r -> scorer.genreScore(r, genreProfile), ctx.rng());
		trending.sort(Comparator.comparingDouble((TitleSearchResponse r) ->
			scorer.genreScore(r, genreProfile) + jitter.getOrDefault(r.externalId(), 0.0)).reversed());
		return trending;
	}

	// "space race" → "Space race stories" / "heist" → "Heist movies": TMDB keyword
	// names are lowercase phrases, so capitalize and suffix by media type
	private String keywordLabel(String name, TitleType type) {
		String display = name.substring(0, 1).toUpperCase() + name.substring(1);
		return display + (type == TitleType.MOVIE ? " movies" : " stories");
	}
}
