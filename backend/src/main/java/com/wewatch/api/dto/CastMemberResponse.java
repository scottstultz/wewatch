package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One top-billed cast member on a title's detail page (#295). Sourced from the
 * credits block that already rides the TMDB detail call (#269), so listing cast
 * costs no extra TMDB request.
 */
public record CastMemberResponse(
	@Schema(description = "TMDB person id")
	long id,
	String name,
	// The role played; TMDB leaves it blank for some credits
	@Schema(description = "The role played; blank for some credits (TMDB doesn't always supply it)")
	String character,
	// Null when TMDB has no headshot for this person
	@Schema(description = "Headshot image URL; null when TMDB has no headshot for this person")
	String profileUrl
) {
}
