package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuggestionDismissalRequest(
	@NotBlank
	@Size(max = 255)
	@Schema(description = "TMDB id of the title to dismiss")
	String tmdbId
) {
}
