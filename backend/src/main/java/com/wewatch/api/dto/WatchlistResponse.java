package com.wewatch.api.dto;

import java.time.Instant;
import java.util.List;

import com.wewatch.api.model.WatchlistType;

public record WatchlistResponse(
	Long id,
	String name,
	WatchlistType type,
	Instant createdAt,
	Instant updatedAt,
	List<WatchlistMemberResponse> members
) {
}
