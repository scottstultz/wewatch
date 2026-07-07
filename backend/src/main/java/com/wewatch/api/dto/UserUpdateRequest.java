package com.wewatch.api.dto;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
	@Email
	@Size(max = 255)
	String email,

	@Size(max = 255)
	String displayName,

	// TMDB watch region, ISO 3166-1 alpha-2 (#270)
	@Pattern(regexp = "[A-Z]{2}")
	String watchRegion,

	// null = unchanged; empty list = clear (turns provider-aware behavior off)
	@Size(max = 100)
	List<Integer> watchProviderIds
) {
}
