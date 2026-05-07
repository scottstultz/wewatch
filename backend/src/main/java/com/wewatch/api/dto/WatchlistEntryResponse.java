package com.wewatch.api.dto;

import java.time.Instant;

import com.wewatch.api.model.WatchStatus;

public record WatchlistEntryResponse(
	Long id,
	Long userId,
	Long titleId,
	WatchStatus status,
	Instant addedAt,
	Instant updatedAt,
	Instant startedAt,
	Instant completedAt
) {
}
