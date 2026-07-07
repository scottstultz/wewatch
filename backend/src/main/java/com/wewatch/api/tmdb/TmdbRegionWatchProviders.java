package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Per-region offers on a title's watch/providers block. Only flatrate
// (subscription streaming) matters to WeWatch — rent/buy listings aren't
// "press play on a service you already pay for".
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbRegionWatchProviders(List<TmdbWatchProvider> flatrate) {}
