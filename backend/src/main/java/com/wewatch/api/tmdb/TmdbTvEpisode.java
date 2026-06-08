package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvEpisode(
	long id,
	@JsonProperty("episode_number") int episodeNumber,
	String name,
	String overview,
	@JsonProperty("air_date") String airDate,
	@JsonProperty("still_path") String stillPath,
	Integer runtime
) {
}
