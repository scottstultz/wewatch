package com.wewatch.api.service;

// Draw policy for the TMDB discover feed, shared by the genre-profile shelves
// and the discover-backed exploration kinds. These two must agree: they query
// the same endpoint over the same catalog, and a vote floor or page depth that
// drifted between them would make the same title eligible on one shelf and not
// the other for no reason a user could perceive.
final class DiscoverPolicy {

	// Per-feed page depth (#249). The daily draw rotates a single page within these
	// bounds — deeper bounds mean the pool the draw can reach before repeating within
	// the suppression window, not more calls per compute.
	// Discover result sets are deep; pages 1–6 stay relevant with the page-1 fallback.
	static final int MAX_FETCH_PAGE = 6;

	// Keeps discover off the long tail of barely-rated titles
	static final int VOTE_COUNT_GTE = 100;

	static final String SORT_POPULARITY = "popularity.desc";
	static final String SORT_VOTE_AVERAGE = "vote_average.desc";

	private DiscoverPolicy() {}
}
