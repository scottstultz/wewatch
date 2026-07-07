package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Fetched via append_to_response=credits on the detail calls (#269) — no
// standalone credits request is ever made.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCredits(
	List<TmdbCastMember> cast,
	List<TmdbCrewMember> crew
) {}
