package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbItem(
	long id,
	@JsonProperty("media_type") String mediaType,
	String title,
	String name,
	String overview,
	@JsonProperty("release_date") String releaseDate,
	@JsonProperty("first_air_date") String firstAirDate,
	@JsonProperty("poster_path") String posterPath
) {
}
