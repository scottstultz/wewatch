package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCollectionDetail(int id, String name, List<TmdbItem> parts) {
}
