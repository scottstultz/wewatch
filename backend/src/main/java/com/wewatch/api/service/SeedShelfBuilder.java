package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.tmdb.TmdbClient;

// The three shelf kinds fed by TMDB's per-title recommendations/similar feeds:
// "Because you added X" (#232), "Because you finished X" (#235), and the
// "More picks for you" catch-all (#266). They live together because they share
// one leftover pool — a seed shelf too thin to stand alone releases its slots
// and folds its candidates into the catch-all — and because they draw from the
// day-seeded rng in a fixed order (see SuggestionContext).
class SeedShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(SeedShelfBuilder.class);

	private static final int MAX_SEEDS = 3;
	private static final int MAX_FINISHED_SEEDS = 1;
	private static final int RECENT_FINISHED_POOL = 5;
	// A standalone seed shelf must carry this many fresh (not recency-penalized)
	// titles (#266) — anything thinner reads as a stub next to full shelves and
	// exhausts within one penalty window. Shelves under the floor fold their
	// candidates into the catch-all MORE_PICKS shelf instead.
	private static final int SEED_SHELF_MIN_FRESH = 6;
	// Vote-count floor for the rich seed tier (#266): a cheap proxy for whether
	// TMDB can sustain a full recommendations/similar shelf for the seed. Niche
	// titles (small docs, festival releases) sit well below it; anything with a
	// mainstream audience sits well above.
	private static final int RICH_SEED_VOTE_COUNT_GTE = 300;
	private static final int SIMILAR_TOP_UP_THRESHOLD = 5;
	// Per-feed page depth (#249). Recommendations/similar genuinely run out after
	// a few pages, so seeds stay shallow.
	private static final int MAX_SEED_FETCH_PAGE = 3;

	private final TmdbClient tmdbClient;
	private final CandidateScorer scorer;
	private final ShelfFiller filler;

	SeedShelfBuilder(TmdbClient tmdbClient, CandidateScorer scorer, ShelfFiller filler) {
		this.tmdbClient = tmdbClient;
		this.scorer = scorer;
		this.filler = filler;
	}

	List<SuggestionShelfResponse> build(SuggestionContext ctx) {
		List<SuggestionShelfResponse> shelves = new ArrayList<>();
		// Candidates from seed shelves that miss the fresh floor pool here for the
		// catch-all MORE_PICKS shelf (#266)
		List<TitleSearchResponse> pooledLeftovers = new ArrayList<>();

		for (WatchlistEntry seed : selectSeeds(ctx)) {
			addSeedShelf(ctx, seed, shelves, pooledLeftovers,
				SuggestionShelfResponse.ShelfKind.PER_SEED,
				name -> "Because you added " + name, "Because of your list");
		}

		for (WatchlistEntry seed : selectFinishedSeeds(ctx)) {
			addSeedShelf(ctx, seed, shelves, pooledLeftovers,
				SuggestionShelfResponse.ShelfKind.FINISHED_SEED,
				name -> "Because you finished " + name, "Because you finished a title");
		}

		addCatchAllShelf(ctx, shelves, pooledLeftovers);
		return shelves;
	}

	// Rich-first seed selection (#266): cached vote count proxies how deep a
	// title's recommendations/similar feeds run — niche seeds yield permanently
	// thin shelves. Each tier is shuffled with the day-seeded rng, so rich seeds
	// rotate among themselves rather than pinning to the top-popularity few, and
	// thin seeds only fill slots the rich tier can't.
	private List<WatchlistEntry> selectSeeds(SuggestionContext ctx) {
		// Sort by id before shuffling: repository ordering isn't guaranteed, and the
		// shuffle must see the same input order to be reproducible within a day
		List<WatchlistEntry> eligible = ctx.entries().stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHING || e.getStatus() == WatchStatus.WANT_TO_WATCH)
			.filter(e -> ctx.titlesById().containsKey(e.getTitleId()))
			.sorted(Comparator.comparing(WatchlistEntry::getId))
			.toList();

		List<WatchlistEntry> richSeeds = new ArrayList<>();
		List<WatchlistEntry> thinSeeds = new ArrayList<>();
		for (WatchlistEntry e : eligible) {
			(isRichSeed(ctx, e) ? richSeeds : thinSeeds).add(e);
		}
		Collections.shuffle(richSeeds, ctx.rng());
		Collections.shuffle(thinSeeds, ctx.rng());

		List<WatchlistEntry> seeds = new ArrayList<>(richSeeds);
		seeds.addAll(thinSeeds);
		return seeds.subList(0, Math.min(MAX_SEEDS, seeds.size()));
	}

	// WATCHED entries never get "Because you added X" shelves (#232); instead
	// the most recent finishes compete for a "Because you finished X" shelf
	private List<WatchlistEntry> selectFinishedSeeds(SuggestionContext ctx) {
		List<WatchlistEntry> finishedPool = ctx.entries().stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHED)
			.filter(e -> ctx.titlesById().containsKey(e.getTitleId()))
			.sorted(Comparator.comparing(WatchlistEntry::getUpdatedAt,
					Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(WatchlistEntry::getId))
			.limit(RECENT_FINISHED_POOL)
			.collect(Collectors.toCollection(ArrayList::new));
		Collections.shuffle(finishedPool, ctx.rng());
		return finishedPool.subList(0, Math.min(MAX_FINISHED_SEEDS, finishedPool.size()));
	}

	// A seed shelf stands alone only if it carries SEED_SHELF_MIN_FRESH fresh
	// titles (#266). Too thin: release the slots it claimed back to the dedup set
	// so its candidates can compete in the catch-all instead.
	private void addSeedShelf(
		SuggestionContext ctx,
		WatchlistEntry seed,
		List<SuggestionShelfResponse> shelves,
		List<TitleSearchResponse> pooledLeftovers,
		SuggestionShelfResponse.ShelfKind kind,
		UnaryOperator<String> label,
		String fallbackLabel
	) {
		Title title = ctx.titlesById().get(seed.getTitleId());
		if (title == null || title.getExternalId() == null) return;

		List<TitleSearchResponse> candidates = fetchScoredCandidates(ctx, title.getType(), title.getExternalId());
		List<TitleSearchResponse> shelf = filler.fill(candidates, ctx.seen(), ctx.recencyWeights(), true);

		if (filler.freshCount(shelf, ctx.recencyWeights()) >= SEED_SHELF_MIN_FRESH) {
			String reason = title.getName() != null ? label.apply(title.getName()) : fallbackLabel;
			shelves.add(new SuggestionShelfResponse(reason, shelf, kind));
		} else {
			shelf.forEach(r -> ctx.seen().remove(r.externalId()));
			pooledLeftovers.addAll(candidates);
		}
	}

	// Seed feeds too thin for a standalone shelf pool their candidates into one
	// "More picks for you" shelf, re-ranked as a single taste-profile-scored
	// pool — a handful of full shelves plus one aggregate instead of a row of
	// 3–4 tile stubs (#266)
	private void addCatchAllShelf(
		SuggestionContext ctx,
		List<SuggestionShelfResponse> shelves,
		List<TitleSearchResponse> pooledLeftovers
	) {
		if (pooledLeftovers.isEmpty()) return;
		List<TitleSearchResponse> pooled = scorer.rankByTasteProfile(pooledLeftovers, ctx);
		List<TitleSearchResponse> shelf = filler.fill(pooled, ctx.seen(), ctx.recencyWeights(), true);
		if (shelf.size() >= ShelfFiller.MIN_SHELF_SIZE) {
			shelves.add(new SuggestionShelfResponse("More picks for you", shelf,
				SuggestionShelfResponse.ShelfKind.MORE_PICKS));
		}
	}

	// Fetch candidates from recommendations + similar top-up, then score by genre
	// affinity plus a boost per shared keyword and per shared person (#269).
	// These endpoints take no provider filter, so streamability enters as a
	// score boost instead (#270).
	private List<TitleSearchResponse> fetchScoredCandidates(SuggestionContext ctx, TitleType type, String tmdbId) {
		// One page draw per seed, shared by recommendations and similar, so RNG
		// consumption doesn't depend on how many results TMDB happens to return
		int page = 1 + ctx.rng().nextInt(MAX_SEED_FETCH_PAGE);
		List<TitleSearchResponse> results = new ArrayList<>();
		try {
			results.addAll(TmdbPaging.fetchPageWithFallback(p -> tmdbClient.getRecommendations(type, tmdbId, p), page));
		} catch (TmdbApiException e) {
			log.warn("Recommendations failed for {}: {}", tmdbId, e.getMessage());
		}
		if (results.size() < SIMILAR_TOP_UP_THRESHOLD) {
			try {
				results.addAll(TmdbPaging.fetchPageWithFallback(p -> tmdbClient.getSimilar(type, tmdbId, p), page));
			} catch (TmdbApiException e) {
				log.warn("Similar failed for {}: {}", tmdbId, e.getMessage());
			}
		}
		return scorer.rankByTasteProfile(results, ctx);
	}

	// Uncached seeds (no tmdb_title_cache row yet, or a pre-#266 row without a
	// vote count) rank as thin until the cache refreshes — a conservative default
	private boolean isRichSeed(SuggestionContext ctx, WatchlistEntry entry) {
		TmdbTitleCache cached = ctx.cachedRowFor(entry);
		return cached != null && cached.getVoteCount() != null
			&& cached.getVoteCount() >= RICH_SEED_VOTE_COUNT_GTE;
	}
}
