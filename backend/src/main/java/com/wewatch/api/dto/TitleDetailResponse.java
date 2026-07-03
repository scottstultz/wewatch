package com.wewatch.api.dto;

import java.util.List;

import com.wewatch.api.model.TitleType;

/**
 * Full title detail for the browse/pre-add detail page, keyed by external id.
 * Carries fields beyond {@link TitleSearchResponse} (genre names, status, and a
 * read-only season summary for TV) sourced live from a TMDB detail call.
 */
public record TitleDetailResponse(
	String externalId,
	String externalSource,
	TitleType type,
	String name,
	String overview,
	String releaseDate,
	String posterUrl,
	String status,
	List<String> genres,
	Double voteAverage,
	Integer voteCount,
	Integer seasonCount,
	List<SeasonSummaryResponse> seasons
) {
}
