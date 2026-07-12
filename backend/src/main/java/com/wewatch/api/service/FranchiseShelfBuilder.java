package com.wewatch.api.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

// "Next in the X" (#272) — a remaining part of a series the user already
// started. The highest-precision suggestion class available, which is why it is
// built first: it gets first pick of the dedup set, ahead of generic per-seed,
// genre, and exploration candidates.
class FranchiseShelfBuilder {

	private static final Logger log = LoggerFactory.getLogger(FranchiseShelfBuilder.class);

	private final TmdbClient tmdbClient;
	private final ShelfFiller filler;

	FranchiseShelfBuilder(TmdbClient tmdbClient, ShelfFiller filler) {
		this.tmdbClient = tmdbClient;
		this.filler = filler;
	}

	// A TMDB collection the user has started (#272): id feeds the /collection/{id}
	// call, name labels the shelf.
	private record FranchiseCandidate(int collectionId, String collectionName) {}

	// One TMDB call when a qualifying collection exists, none otherwise (#272's
	// call-budget requirement). Parts are ordered by release date — the
	// franchise itself is the coherence axis, so no genre diversification and
	// no taste-profile re-ranking, just chronology. Owned parts fall out via the
	// shared seen-dedup set in the filler; no MIN_SHELF_SIZE floor, since a
	// single remaining sequel is still the single best suggestion available, and
	// it isn't capped by MAX_EXPLORATION_SHELVES either.
	// Exempt from the recency penalty (the issue's "a sequel staying visible is
	// a feature, not staleness"): the demotion could only reorder this shelf,
	// never shrink it, and a part shown recently on some other shelf sinking
	// below a later one would break the release-order contract — so the filler
	// gets an empty weight map. Impressions are still recorded on serve.
	SuggestionShelfResponse build(SuggestionContext ctx) {
		List<FranchiseCandidate> candidates = franchiseCandidates(ctx);
		if (candidates.isEmpty()) return null;

		FranchiseCandidate franchise = candidates.get(ctx.rng().nextInt(candidates.size()));
		List<TitleSearchResponse> parts;
		try {
			parts = tmdbClient.getCollectionParts(franchise.collectionId());
		} catch (TmdbApiException e) {
			log.warn("Franchise shelf failed for collection {}: {}", franchise.collectionId(), e.getMessage());
			return null;
		}

		// Unreleased parts are excluded rather than badged (#272 scope choice):
		// an announced sequel the user can't press play on isn't a suggestion.
		// A null release date on a collection part means unannounced/undated —
		// unwatchable either way, so it counts as unreleased (unlike #239's
		// null-means-aired episode rule, where blocking would strand progress).
		LocalDate today = ctx.today();
		List<TitleSearchResponse> released = parts.stream()
			.filter(p -> p.releaseDate() != null && !p.releaseDate().isAfter(today))
			.sorted(Comparator.comparing(TitleSearchResponse::releaseDate))
			.toList();
		List<TitleSearchResponse> shelf = filler.fill(released, ctx.seen(), Map.of(), false);
		if (shelf.isEmpty()) return null;

		String name = franchise.collectionName() != null ? franchise.collectionName() : "this series";
		return new SuggestionShelfResponse("Next in the " + name, shelf,
			SuggestionShelfResponse.ShelfKind.FRANCHISE);
	}

	// WATCHED/WATCHING movie entries with a cached collection id (#272).
	// WANT_TO_WATCH is excluded — franchise continuation is a completed-interest
	// signal, not a taste-profile one. Ties break by id so the daily rng never
	// touches candidate construction (#231), matching the person/keyword profiles.
	private List<FranchiseCandidate> franchiseCandidates(SuggestionContext ctx) {
		Map<Integer, String> collections = new HashMap<>();
		for (WatchlistEntry e : ctx.entries()) {
			if (e.getStatus() != WatchStatus.WATCHED && e.getStatus() != WatchStatus.WATCHING) continue;
			Title t = ctx.titlesById().get(e.getTitleId());
			if (t == null || t.getType() != TitleType.MOVIE || t.getExternalId() == null) continue;
			TmdbTitleCache cached = ctx.cacheByTmdbId().get(t.getExternalId());
			if (cached == null || cached.getCollectionId() == null) continue;
			collections.putIfAbsent(cached.getCollectionId(), cached.getCollectionName());
		}
		return collections.entrySet().stream()
			.map(en -> new FranchiseCandidate(en.getKey(), en.getValue()))
			.sorted(Comparator.comparingInt(FranchiseCandidate::collectionId))
			.toList();
	}
}
