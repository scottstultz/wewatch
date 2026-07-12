package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// /3/genre/{movie|tv}/list — the only place TMDB hands out genre *names*. Titles
// carry bare ids, so this is what turns tmdb_title_cache.genre_ids into labels (#323).
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenreListResponse(List<TmdbGenre> genres) {}
