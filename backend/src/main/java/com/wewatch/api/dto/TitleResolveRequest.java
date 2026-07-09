package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.wewatch.api.model.TitleType;

import io.swagger.v3.oas.annotations.media.Schema;

public record TitleResolveRequest(
	@NotBlank
	@Size(max = 255)
	String externalId,

	@NotBlank
	@Size(max = 100)
	@Schema(description = "External source of the title. Only \"TMDB\" is supported (case-insensitive).")
	String externalSource,

	@NotNull
	TitleType type
) {
}
