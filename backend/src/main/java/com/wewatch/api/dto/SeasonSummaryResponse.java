package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SeasonSummaryResponse(
	@Schema(description = "1-based season number; season 0 (\"Specials\") is excluded")
	int seasonNumber,
	String name,
	int episodeCount,
	String posterUrl,
	String airDate
) {
}
