package com.wewatch.api.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserResponse(
	Long id,
	String email,
	String displayName,
	Instant createdAt,
	Instant updatedAt,
	@Schema(description = "TMDB watch region, ISO 3166-1 alpha-2 (e.g. \"US\"); null if the user "
		+ "hasn't configured one (#270)")
	String watchRegion,
	@Schema(description = "TMDB provider ids for the user's selected streaming services; null or "
		+ "empty means provider-aware suggestions are off (#270)")
	List<Integer> watchProviderIds
) {
}
