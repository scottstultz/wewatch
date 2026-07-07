package com.wewatch.api.model;

// A TMDB keyword as cached on tmdb_title_cache (#271). The id feeds discover
// with_keywords queries; the name is only for shelf labels — affinity scoring
// keeps reading the flat keyword_ids column.
public record CachedKeyword(int id, String name) {
}
