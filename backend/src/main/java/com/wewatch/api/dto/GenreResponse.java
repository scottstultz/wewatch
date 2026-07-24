package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// One TMDB genre, id plus display name (#381). The ids are the same ones stored on
// tmdb_title_cache.genre_ids and carried on WatchlistEntryResponse.genreIds, so a
// client can label an entry's genres without a second lookup.
public record GenreResponse(
	@Schema(description = "TMDB genre id")
	int id,
	String name
) {
}
