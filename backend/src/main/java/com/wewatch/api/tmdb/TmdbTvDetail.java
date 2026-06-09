package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvDetail(
	long id,
	@JsonProperty("number_of_seasons") int numberOfSeasons,
	String status,
	@JsonProperty("first_air_date") String firstAirDate,
	List<TmdbTvSeason> seasons
) {
}
