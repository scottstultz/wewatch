package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.wewatch.api.model.TitleType;

public record TitleResolveRequest(
	@NotBlank
	@Size(max = 255)
	String externalId,

	@NotBlank
	@Size(max = 100)
	String externalSource,

	@NotNull
	TitleType type
) {
}
