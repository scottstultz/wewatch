package com.wewatch.api.dto;

public record EpisodeResponse(
	int episodeNumber,
	String name,
	String overview,
	String airDate,
	String stillUrl,
	Integer runtimeMinutes
) {
}
