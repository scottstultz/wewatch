package com.wewatch.api.dto;

import java.time.LocalDate;
import java.util.List;

import com.wewatch.api.model.TitleType;

public record TitleSearchResponse(
	String externalId,
	String externalSource,
	TitleType type,
	String name,
	String overview,
	LocalDate releaseDate,
	String posterUrl,
	List<Integer> genreIds
) {
}
