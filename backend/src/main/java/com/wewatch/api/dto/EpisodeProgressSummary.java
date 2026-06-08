package com.wewatch.api.dto;

public record EpisodeProgressSummary(
	long watchedCount,
	Integer lastWatchedSeason,
	Integer lastWatchedEpisode
) {
}
