package com.wewatch.api.service;

import java.util.List;
import java.util.function.IntFunction;

import com.wewatch.api.dto.TitleSearchResponse;

// How the shelf builders page into a TMDB feed (#249). Every feed here is drawn
// at a day-seeded page rather than always page 1, which is what lets the pool a
// shelf can reach exceed one page without costing extra calls per compute.
final class TmdbPaging {

	// Deeper pages can be empty (few recommendations, thin discover results) — fall
	// back to page 1 rather than dropping the shelf for falling under MIN_SHELF_SIZE
	static List<TitleSearchResponse> fetchPageWithFallback(IntFunction<List<TitleSearchResponse>> fetch, int page) {
		List<TitleSearchResponse> results = fetch.apply(page);
		return results.isEmpty() && page > 1 ? fetch.apply(1) : results;
	}

	private TmdbPaging() {}
}
