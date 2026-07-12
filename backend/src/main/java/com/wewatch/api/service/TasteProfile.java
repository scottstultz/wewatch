package com.wewatch.api.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wewatch.api.model.TitleType;

// What the watchlist says about its members' taste, derived once per compute
// and read by every downstream stage. Nothing here depends on the daily rng or
// on TMDB: profile construction is stable across recomputes by design (#231),
// so the same list yields the same profile all day.
//
// The id sets are the scoring view of the affinity lists (a shared keyword or
// person boosts a candidate's score); the lists themselves carry the display
// names the KEYWORD and PERSON shelves are labeled with.
record TasteProfile(
	Map<Integer, Double> genreProfile,
	List<KeywordAffinity> keywordAffinities,
	Set<Integer> keywordIds,
	List<PersonAffinity> personAffinities,
	Set<Integer> personIds,
	Map<TitleType, List<Integer>> topGenresByType,
	TitleType dominantType
) {

	// How many keywords represent a list. One knob, two readers: the top-N cut on
	// the affinity list itself, and the type-scoped keyword filter GenreShelfBuilder
	// sends to discover. They were a single constant before the pipeline was split
	// up (#319) and are kept that way deliberately — a profile that scores on more
	// keywords than it filters on (or vice versa) isn't a coherent setting.
	static final int MAX_KEYWORDS = 5;

	static TasteProfile of(
		Map<Integer, Double> genreProfile,
		List<KeywordAffinity> keywordAffinities,
		List<PersonAffinity> personAffinities,
		Map<TitleType, List<Integer>> topGenresByType,
		TitleType dominantType
	) {
		return new TasteProfile(
			genreProfile,
			keywordAffinities,
			keywordAffinities.stream().map(KeywordAffinity::id).collect(Collectors.toSet()),
			personAffinities,
			personAffinities.stream().map(PersonAffinity::id).collect(Collectors.toSet()),
			topGenresByType,
			dominantType);
	}

	// The genre-profile discover filter for a medium (#265): the top genres the
	// user's titles of that type carry. Empty when the type has no cached genre
	// data, which is what makes the caller skip the shelf.
	List<Integer> topGenres(TitleType type) {
		return topGenresByType.getOrDefault(type, List.of());
	}

	// Exploration shelves are built for the medium the list leans toward (#235),
	// so they filter on that medium's genres
	List<Integer> dominantGenres() {
		return topGenres(dominantType);
	}
}
