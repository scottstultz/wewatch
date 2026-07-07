package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCrewMember(
	long id,
	String name,
	String job
) {}
