package com.wewatch.api.dto;

import java.util.List;

import com.wewatch.api.model.TitleType;

/**
 * Full title detail for the browse/pre-add detail page, keyed by external id.
 * Carries fields beyond {@link TitleSearchResponse} (genre names, status, a
 * read-only season summary for TV, and where the title streams in the caller's
 * watch region, #270) sourced live from a TMDB detail call.
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
	List<SeasonSummaryResponse> seasons,
	// The region the providers were resolved for — the caller's setting, or the
	// US default when unset
	String watchRegion,
	List<WatchProviderResponse> watchProviders
) {
}
