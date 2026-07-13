package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// The per-title videos block (#340) — trailers, teasers, clips and featurettes
// across several sites. Rides detail calls via append_to_response;
// TrailerPicker reduces it to the one video worth linking to.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbVideos(List<TmdbVideo> results) {}
