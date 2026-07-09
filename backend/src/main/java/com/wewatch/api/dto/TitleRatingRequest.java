package com.wewatch.api.dto;

import jakarta.validation.constraints.NotNull;

import com.wewatch.api.model.Rating;

import io.swagger.v3.oas.annotations.media.Schema;

public record TitleRatingRequest(
	@NotNull
	@Schema(description = "Thumbs rating to set. UP and DOWN are the only values — there is no "
		+ "magnitude, only sign (#273).")
	Rating rating
) {
}
