package com.wewatch.api.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record EpisodeProgressResponse(
	Long id,
	Long watchlistEntryId,
	int seasonNumber,
	int episodeNumber,
	boolean watched,
	@Schema(description = "When the episode was marked watched; null if watched is false")
	Instant watchedAt
) {
}
