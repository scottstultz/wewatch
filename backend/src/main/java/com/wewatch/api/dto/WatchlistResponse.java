package com.wewatch.api.dto;

import java.time.Instant;
import java.util.List;

import com.wewatch.api.model.WatchlistType;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchlistResponse(
	Long id,
	String name,
	@Schema(description = "PERSONAL watchlists are auto-provisioned per user and cannot be deleted; "
		+ "SHARED watchlists can have multiple members and can be deleted by their owner.")
	WatchlistType type,
	Instant createdAt,
	Instant updatedAt,
	List<WatchlistMemberResponse> members,
	@Schema(description = "Whether this is the caller's default watchlist, not a global property of the watchlist")
	boolean isDefault
) {
}
