package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuggestionDismissalRequest(
	@NotBlank
	@Size(max = 255)
	String tmdbId
) {
}
