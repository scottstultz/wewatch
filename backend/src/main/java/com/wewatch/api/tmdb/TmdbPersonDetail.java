package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonDetail(
	long id,
	String name,
	String biography,
	@JsonProperty("profile_path") String profilePath,
	@JsonProperty("known_for_department") String knownForDepartment,
	String birthday,
	@JsonProperty("place_of_birth") String placeOfBirth,
	@JsonProperty("combined_credits") TmdbPersonCredits combinedCredits
) {
}
