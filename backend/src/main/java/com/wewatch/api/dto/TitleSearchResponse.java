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
	List<Integer> genreIds,
	// Streaming services (of the viewer's configured ones) carrying this title
	// in their region (#270). null = unknown or provider settings not
	// configured — most call sites (search, TMDB feeds) have no provider data;
	// only suggestion shelves attach it, from tmdb_title_cache.
	List<Integer> providerIds
) {
	public TitleSearchResponse(String externalId, String externalSource, TitleType type, String name,
			String overview, LocalDate releaseDate, String posterUrl, List<Integer> genreIds) {
		this(externalId, externalSource, type, name, overview, releaseDate, posterUrl, genreIds, null);
	}
}
