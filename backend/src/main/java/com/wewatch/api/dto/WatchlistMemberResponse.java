package com.wewatch.api.dto;

import java.time.Instant;

import com.wewatch.api.model.MemberRole;

public record WatchlistMemberResponse(
	Long userId,
	String email,
	String displayName,
	MemberRole role,
	Instant joinedAt
) {
}
