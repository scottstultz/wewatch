package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One person hit from the Discover multi search (#356). TMDB's
 * {@code /search/multi} already returns {@code person} items alongside titles;
 * this surfaces them as a slim, clickable "People" row that links to the
 * existing person page ({@code /api/people/{id}}), so it costs no extra TMDB
 * request. Ranked by TMDB popularity.
 */
public record PersonSearchResult(
	@Schema(description = "TMDB person id, as consumed by the person detail endpoint")
	long id,
	String name,
	// Null when TMDB has no headshot for this person — the client renders a silhouette
	@Schema(description = "Headshot image URL; null when TMDB has no headshot for this person")
	String profileUrl
) {
}
