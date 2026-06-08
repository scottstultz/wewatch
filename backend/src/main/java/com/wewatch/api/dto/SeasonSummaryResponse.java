package com.wewatch.api.dto;

public record SeasonSummaryResponse(
	int seasonNumber,
	String name,
	int episodeCount,
	String posterUrl,
	String airDate
) {
}
