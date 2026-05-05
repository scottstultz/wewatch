package com.wewatch.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.wewatch.api.model.TitleType;

public record TitleCreateRequest(
	@NotBlank
	@Size(max = 255)
	String externalId,

	@NotBlank
	@Size(max = 100)
	String externalSource,

	@NotNull
	TitleType type,

	@NotBlank
	@Size(max = 255)
	String name,

	@Size(max = 4000)
	String overview,

	LocalDate releaseDate,

	@Size(max = 2048)
	String posterUrl
) {
}
