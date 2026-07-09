package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// order is TMDB's billing order — 0 is the top-billed lead
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCastMember(
	long id,
	String name,
	String character,
	@JsonProperty("profile_path") String profilePath,
	Integer order
) {}
