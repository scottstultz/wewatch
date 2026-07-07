package com.wewatch.api.dto;

import java.time.Instant;
import java.util.List;

public record UserResponse(
	Long id,
	String email,
	String displayName,
	Instant createdAt,
	Instant updatedAt,
	String watchRegion,
	List<Integer> watchProviderIds
) {
}
