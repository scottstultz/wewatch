package com.wewatch.api.dto;

import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

// No email field: the address is the account's identity and is not self-service updatable (#342).
// A body carrying "email" is ignored rather than rejected — unknown properties are not fatal — so
// the endpoint fails closed for any client still sending one.
public record UserUpdateRequest(
	@Size(max = 255)
	String displayName,

	// TMDB watch region, ISO 3166-1 alpha-2 (#270)
	@Pattern(regexp = "[A-Z]{2}")
	@Schema(description = "TMDB watch region, ISO 3166-1 alpha-2 (e.g. \"US\"). Null leaves the "
		+ "current value unchanged (#270).")
	String watchRegion,

	// null = unchanged; empty list = clear (turns provider-aware behavior off)
	@Size(max = 100)
	@Schema(description = "TMDB provider ids for the user's selected streaming services. Null leaves "
		+ "the current value unchanged; an empty list clears it, turning provider-aware suggestions "
		+ "off (#270).")
	List<Integer> watchProviderIds
) {
}
