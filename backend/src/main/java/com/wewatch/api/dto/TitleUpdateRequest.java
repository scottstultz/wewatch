package com.wewatch.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.wewatch.api.model.TitleType;

public record TitleUpdateRequest(
	@Pattern(regexp = ".*\\S.*")
	@Size(max = 255)
	String name,

	@Size(max = 4000)
	String overview,

	LocalDate releaseDate,

	@Size(max = 2048)
	String posterUrl,

	TitleType type
) {
}
