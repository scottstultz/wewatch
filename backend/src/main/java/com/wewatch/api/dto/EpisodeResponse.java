package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EpisodeResponse(
	int episodeNumber,
	String name,
	String overview,
	String airDate,
	String stillUrl,
	@Schema(description = "Runtime in minutes; null when TMDB has no runtime for this episode")
	Integer runtimeMinutes
) {
}
