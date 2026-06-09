package com.wewatch.api.dto;

public record EpisodeProgressSummary(
	long watchedCount,
	Integer lastWatchedSeason,
	Integer lastWatchedEpisode,
	Integer nextSeason,
	Integer nextEpisode,
	String nextEpisodeName,
	java.time.LocalDate nextAirDate,
	Integer nextRuntimeMinutes,
	String showStatus
) {
}
