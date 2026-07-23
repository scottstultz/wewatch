package com.wewatch.api.dto;

import com.wewatch.api.model.TitleType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One watchlist entry that fits the requested time window (#359).
 *
 * <p>Deliberately slim: the caller already holds the entries, so this carries the entry id to join
 * on plus the runtime the decision was made from.
 */
@Schema(description = "A watchlist entry that fits the requested time window, and the runtime it fits on.")
public record TonightPickResponse(
	Long entryId,
	@Schema(description = "Whether the title is a movie or a TV show", allowableValues = {"MOVIE", "TV"})
	TitleType type,
	@Schema(description = "For a movie its own runtime; for a show the runtime of the next unwatched "
		+ "episode. Never null — a title with no known runtime cannot be judged to fit and is left out.")
	Integer runtimeMinutes,
	@Schema(description = "Season of the next unwatched episode; null for movies")
	Integer nextSeason,
	@Schema(description = "Number of the next unwatched episode; null for movies")
	Integer nextEpisode
) {
}
