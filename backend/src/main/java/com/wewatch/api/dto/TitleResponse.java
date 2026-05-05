package com.wewatch.api.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.wewatch.api.model.TitleType;

public record TitleResponse(
	Long id,
	String externalId,
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
