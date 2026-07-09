package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EpisodeProgressSummary(
	long watchedCount,
	@Schema(description = "Season number of the last watched episode; null if nothing watched yet")
	Integer lastWatchedSeason,
	@Schema(description = "Episode number (within lastWatchedSeason) of the last watched episode; "
		+ "null if nothing watched yet")
	Integer lastWatchedEpisode,
	@Schema(description = "Season number of the next unwatched episode; null if there is none")
	Integer nextSeason,
	@Schema(description = "Episode number (within nextSeason) of the next unwatched episode; null "
		+ "if there is none")
	Integer nextEpisode,
	String nextEpisodeName,
	@Schema(description = "Air date of the next unwatched episode; null if unaired or unknown")
	java.time.LocalDate nextAirDate,
	Integer nextRuntimeMinutes,
	@Schema(description = "TMDB show status (e.g. \"Returning Series\", \"Ended\", \"Canceled\")")
	String showStatus
) {
}
