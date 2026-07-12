package com.wewatch.api.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A show on the watchlist whose next episode airs inside the requested window.")
public record ReturningEpisodeResponse(
	@Schema(description = "Watchlist entry id of the show — deep-link with this, not the TMDB id")
	Long entryId,
	String externalId,
	String externalSource,
	@Schema(description = "Name of the show, not of the episode")
	String showName,
	String posterUrl,
	Integer seasonNumber,
	Integer episodeNumber,
	@Schema(description = "Title of the upcoming episode; TMDB leaves this blank for some unaired episodes")
	String episodeName,
	@Schema(description = "Air date of the upcoming episode; always inside the requested window")
	LocalDate airDate,
	Integer runtimeMinutes
) {
}
