package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TMDB's genre catalogs, one list per medium (#381).
 *
 * <p>Kept separate rather than merged because the catalogs genuinely differ: "Action" is movie id
 * 28, "Action &amp; Adventure" is TV id 10759, and they are distinct genres. A flat list would show
 * both with no way to tell which medium each belongs to. Callers that only need to label an id
 * regardless of medium can flatten this themselves.
 */
public record GenreCatalogResponse(
	@Schema(description = "TMDB's movie genre catalog, sorted by name")
	List<GenreResponse> movie,
	@Schema(description = "TMDB's TV genre catalog, sorted by name")
	List<GenreResponse> tv
) {
}
