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
	String character
) {
}
