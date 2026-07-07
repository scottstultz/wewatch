package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbWatchRegion(
	@JsonProperty("iso_3166_1") String code,
	@JsonProperty("english_name") String englishName
) {}
