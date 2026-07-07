package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// order is TMDB's billing order — 0 is the top-billed lead
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCastMember(
	long id,
	String name,
	Integer order
) {}
