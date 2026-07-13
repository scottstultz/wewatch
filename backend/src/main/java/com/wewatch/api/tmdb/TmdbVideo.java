package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// A single video attached to a title (#340). `official` is boxed — TMDB omits
// it on older rows.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbVideo(
	String key,
	String site,
	String type,
	Boolean official,
	@JsonProperty("published_at") String publishedAt
) {}
