package com.wewatch.api.model;

// A TMDB person as cached on tmdb_title_cache (#269). The id feeds affinity
// scoring and discover with_people queries; the name is only for shelf labels.
public record CachedPerson(int id, String name) {
}
