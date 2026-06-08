package com.wewatch.api.dto;

import java.time.Instant;

public record EpisodeProgressResponse(
	Long id,
	Long watchlistEntryId,
	int seasonNumber,
	int episodeNumber,
	boolean watched,
	Instant watchedAt
) {
}
