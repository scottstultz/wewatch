package com.wewatch.api.dto;

/**
 * One top-billed cast member on a title's detail page (#295). Sourced from the
 * credits block that already rides the TMDB detail call (#269), so listing cast
 * costs no extra TMDB request.
 */
public record CastMemberResponse(
	long id,
	String name,
	// The role played; TMDB leaves it blank for some credits
	String character,
	// Null when TMDB has no headshot for this person
	String profileUrl
) {
}
