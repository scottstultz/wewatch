package com.wewatch.api.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record PersonDetailResponse(
	@Schema(description = "TMDB person id")
	long id,
	String name,
	String biography,
	@Schema(description = "Headshot URL; null when TMDB has no profile image for this person, "
		+ "in which case the client renders a silhouette placeholder")
	String profileUrl,
	@Schema(description = "TMDB's primary department for this person, e.g. \"Acting\" or \"Directing\"")
	String knownForDepartment,
	LocalDate birthday,
	String placeOfBirth,
	@Schema(description = "Acting credits across movies and TV, deduped and most popular first. "
		+ "This is the only response that populates TitleSearchResponse.character — the role the "
		+ "person is credited as (#401); where several credits collapse onto one title, the one "
		+ "covering the most episodes wins")
	List<TitleSearchResponse> credits
) {
}
