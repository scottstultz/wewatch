package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// A single acting credit from /3/person/{id}?append_to_response=combined_credits (#304).
// Deliberately not TmdbItem: the filmography's filter and sort need popularity,
// voteCount, and character, and widening TmdbItem's canonical constructor would
// churn every test that builds one by hand.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonCredit(
	long id,
	@JsonProperty("media_type") String mediaType,
	String title,
	String name,
	String overview,
	@JsonProperty("release_date") String releaseDate,
	@JsonProperty("first_air_date") String firstAirDate,
	@JsonProperty("poster_path") String posterPath,
	@JsonProperty("genre_ids") List<Integer> genreIds,
	Double popularity,
	@JsonProperty("vote_count") Integer voteCount,
	String character,
	// TV-only: how many episodes this credit covers. Breaks the dedup tie when a
	// person holds several credits on one show (#401) — popularity is a per-title
	// score, so every credit on the same show ties on it. Null on movie credits,
	// which carry "order" (billing position) instead.
	@JsonProperty("episode_count") Integer episodeCount
) {
	// Delegating overload so the credit fixtures built by hand keep compiling,
	// same precedent as TitleSearchResponse's (#374).
	public TmdbPersonCredit(long id, String mediaType, String title, String name, String overview,
			String releaseDate, String firstAirDate, String posterPath, List<Integer> genreIds,
			Double popularity, Integer voteCount, String character) {
		this(id, mediaType, title, name, overview, releaseDate, firstAirDate, posterPath, genreIds,
			popularity, voteCount, character, null);
	}
}
