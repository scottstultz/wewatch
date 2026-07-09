package com.wewatch.api.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.wewatch.api.model.TitleType;

import io.swagger.v3.oas.annotations.media.Schema;

public record TitleResponse(
	Long id,
	String externalId,
	@Schema(description = "External source of the title. Only \"TMDB\" is currently supported.")
	String externalSource,
	TitleType type,
	String name,
	String overview,
	LocalDate releaseDate,
	String posterUrl,
	Instant createdAt,
	Instant updatedAt
) {
}
