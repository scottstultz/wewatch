package com.wewatch.api.dto;

import java.time.Instant;

public record UserResponse(
	Long id,
	String email,
	String displayName,
	Instant createdAt,
	Instant updatedAt
) {
}
