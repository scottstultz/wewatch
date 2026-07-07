package com.wewatch.api.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// One streaming service as TMDB (via JustWatch) describes it — shared between
// the per-title watch/providers block and the /watch/providers/{type} listing.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbWatchProvider(
	@JsonProperty("provider_id") int providerId,
	@JsonProperty("provider_name") String providerName,
	@JsonProperty("logo_path") String logoPath,
	@JsonProperty("display_priority") Integer displayPriority
) {}
