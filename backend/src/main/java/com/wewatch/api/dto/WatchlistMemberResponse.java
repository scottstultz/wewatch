package com.wewatch.api.dto;

import java.time.Instant;

import com.wewatch.api.model.MemberRole;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchlistMemberResponse(
	Long userId,
	String email,
	String displayName,
	@Schema(description = "Member's role on this watchlist", allowableValues = {"OWNER", "EDITOR", "VIEWER"})
	MemberRole role,
	Instant joinedAt
) {
}
