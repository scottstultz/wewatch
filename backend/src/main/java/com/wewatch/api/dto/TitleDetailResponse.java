package com.wewatch.api.dto;

import java.util.List;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.TitleType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Full title detail for the browse/pre-add detail page, keyed by external id.
 * Carries fields beyond {@link TitleSearchResponse} (genre names, status, a
 * read-only season summary for TV, where the title streams in the caller's
 * watch region (#270), and the top-billed cast, #295) sourced live from a TMDB
 * detail call.
 */
public record TitleDetailResponse(
	String externalId,
	@Schema(description = "External source of the title. Only \"TMDB\" is currently supported.")
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
	// Total runtime in minutes for movies; null for TV (episode runtimes live
	// on the per-episode detail instead)
	Integer runtimeMinutes,
	Integer seasonCount,
	List<SeasonSummaryResponse> seasons,
	// The region the providers were resolved for — the caller's setting, or the
	// US default when unset
	@Schema(description = "TMDB watch region the providers below were resolved for — the caller's "
		+ "configured region, or \"US\" if unset (#270)")
	String watchRegion,
	List<WatchProviderResponse> watchProviders,
	// Internal title id when a titles row exists for this external id (#273) —
	// null for titles nobody has resolved yet; the ratings API is keyed on it
	@Schema(description = "Local titles row id when this title has already been resolved (see "
		+ "/api/titles/resolve); null if nobody has resolved it yet. The ratings API "
		+ "(/api/titles/{titleId}/rating) is keyed on this id.")
	Long titleId,
	// The caller's own thumbs rating (#273); null when unrated or no title row
	@Schema(description = "The caller's own thumbs rating; null when unrated or when titleId is null")
	Rating myRating,
	// Top-billed cast in TMDB billing order (#295); empty when TMDB has no
	// credits for the title
	List<CastMemberResponse> cast,
	// A ready-to-open YouTube URL for the title's trailer (#340), like posterUrl
	// — null when TMDB has no trailer or teaser, and the link is then not rendered
	@Schema(description = "YouTube watch URL for the title's trailer; null when TMDB has none (#340)")
	String trailerUrl
) {
}
