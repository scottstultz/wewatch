package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.TitleSearchResponse;

// The one place ranked candidates become a shelf: cross-shelf dedup, the
// recency demotion (#264), and optional genre diversification (#265). Every
// shelf kind funnels through fill(), which is what makes a title appear at most
// once across the whole shelf set.
class ShelfFiller {

	static final int MIN_SHELF_SIZE = 3;
	static final int MAX_SHELF_SIZE = 12;
	// Same-genre run cap for feeds with a real genre mix (recommendations/similar/
	// trending). Discover-backed feeds are exempt (#265): they are filtered to the
	// user's top genres by construction, so nearly every candidate shares a genre
	// and the cap would chop a 20-result page to ~4 before the shelf fills.
	private static final int MAX_PER_GENRE_CLUSTER = 4;

	private final SuggestionTuningProperties tuning;

	ShelfFiller(SuggestionTuningProperties tuning) {
		this.tuning = tuning;
	}

	// Fill a shelf, optionally diversified: with diversify on, a candidate is skipped
	// once every genre it carries is at MAX_PER_GENRE_CLUSTER — keyed on the full
	// genre set, not TMDB's arbitrary first genre id (#265), so a title bringing any
	// fresh genre still enters.
	// Recently shown candidates (#264) are demoted, not held back: a stable re-sort
	// pushes each one down by RECENCY_DEMOTION positions scaled by its recency weight
	// (1.0 = shown yesterday, decaying to 1/window at the window edge). Shown-yesterday
	// sinks past a full shelf and resurfaces only when the pool is thin, so shelves
	// fill toward MAX_SHELF_SIZE instead of pinning at a suppression floor, while a
	// title near the window edge recovers most of its rank.
	List<TitleSearchResponse> fill(
		List<TitleSearchResponse> candidates,
		Set<String> seen,
		Map<String, Double> recencyWeights,
		boolean diversify
	) {
		List<TitleSearchResponse> ranked = IntStream.range(0, candidates.size()).boxed()
			.sorted(Comparator.comparingDouble((Integer i) ->
				i + tuning.getRecencyDemotion() * recencyWeights.getOrDefault(candidates.get(i).externalId(), 0.0)))
			.map(candidates::get)
			.toList();
		Map<Integer, Integer> genreCount = new HashMap<>();
		List<TitleSearchResponse> shelf = new ArrayList<>();
		for (TitleSearchResponse r : ranked) {
			if (!seen.add(r.externalId())) continue;
			List<Integer> genres = r.genreIds() != null ? r.genreIds() : List.of();
			if (diversify && allGenresSaturated(genres, genreCount)) continue;
			shelf.add(r);
			if (diversify) genres.forEach(g -> genreCount.merge(g, 1, Integer::sum));
			if (shelf.size() >= MAX_SHELF_SIZE) break;
		}
		return shelf;
	}

	// Fresh = not recency-penalized: the shelf titles the user hasn't been shown
	// within the penalty window. The standalone floor counts only these (#266),
	// so a shelf padded out with re-served titles still folds into the catch-all.
	long freshCount(List<TitleSearchResponse> shelf, Map<String, Double> recencyWeights) {
		return shelf.stream()
			.filter(r -> recencyWeights.getOrDefault(r.externalId(), 0.0) == 0.0)
			.count();
	}

	// Genre-less candidates are never capped, matching the old primary-genre rule
	private boolean allGenresSaturated(List<Integer> genres, Map<Integer, Integer> genreCount) {
		return !genres.isEmpty()
			&& genres.stream().allMatch(g -> genreCount.getOrDefault(g, 0) >= MAX_PER_GENRE_CLUSTER);
	}
}
