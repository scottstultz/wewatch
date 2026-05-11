package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSearchPage(List<TmdbItem> results) {
}
