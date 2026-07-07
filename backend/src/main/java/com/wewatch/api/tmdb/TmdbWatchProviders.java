package com.wewatch.api.tmdb;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// The per-title watch/providers block (#270), keyed by watch region
// (ISO 3166-1 alpha-2). Rides detail calls via append_to_response.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbWatchProviders(Map<String, TmdbRegionWatchProviders> results) {}
