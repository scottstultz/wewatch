package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// TMDB keyword responses are asymmetric:
//   movies → { "keywords": [...] }
//   TV     → { "results":  [...] }
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbKeywordsResponse(
	List<TmdbKeyword> keywords,
	List<TmdbKeyword> results
) {}
