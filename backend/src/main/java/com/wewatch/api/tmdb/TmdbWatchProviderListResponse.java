package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// /3/watch/providers/{movie|tv} listing; with watch_region set, each entry's
// display_priority is already the region-local one.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbWatchProviderListResponse(List<TmdbWatchProvider> results) {}
