package com.wewatch.api.tuning;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A fixture watchlist loaded from {@code src/test/resources/tuning/watchlists/*.json}
 * (#288). Deliberately data, not code, so a new regime is a new JSON file — see
 * the tuning README. Each entry carries the taste-profile inputs the pipeline
 * reads off {@code tmdb_title_cache} (genres, keywords, cast, directors, vote
 * count, providers) plus the per-user status/rating/age; candidates are then
 * served by the shared {@link SyntheticCatalog}, so a fixture controls the
 * profile while the catalog controls what TMDB "returns".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record FixtureWatchlist(
	String name,
	String description,
	long watchlistId,
	List<Member> members,
	List<Entry> entries,
	List<String> dismissed
) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Member(long userId, String region, List<Integer> providerIds) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Entry(
		long titleId,
		String tmdbId,
		String type,
		String name,
		String status,
		// updated_at is BASE_DAY - ageDays; drives the #274 recency decay
		int ageDays,
		// UP / DOWN / null (unrated)
		String rating,
		List<Integer> genreIds,
		List<Integer> keywordIds,
		List<Integer> castIds,
		List<Integer> directorIds,
		Integer voteCount,
		// US streaming provider ids carrying this title, for #270 availability
		List<Integer> providerIds,
		String releaseDate,
		Integer collectionId,
		String collectionName
	) {}
}
