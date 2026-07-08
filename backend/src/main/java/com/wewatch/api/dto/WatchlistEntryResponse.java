package com.wewatch.api.dto;

import java.time.Instant;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchStatus;

public record WatchlistEntryResponse(
	Long id,
	Long watchlistId,
	Long addedByUserId,
	Long titleId,
	String externalId,
	String externalSource,
	String name,
	TitleType type,
	String posterUrl,
	WatchStatus status,
	Instant addedAt,
	Instant updatedAt,
	Instant startedAt,
	Instant completedAt,
	EpisodeProgressSummary episodeProgress,
	// The caller's own thumbs rating (#273) — personal, not the entry's;
	// two members of a shared list see different values here
	Rating myRating
) {
}
