package com.wewatch.api.dto;

import java.util.List;

public record SuggestionShelfResponse(
	String reason,
	List<TitleSearchResponse> titles,
	ShelfKind kind,
	// true when the shelf's TMDB feed was restricted to the members' streaming
	// services (#270) — every title on it is streamable even where per-title
	// badge data is missing from the cache
	boolean providerFiltered
) {
	public SuggestionShelfResponse(String reason, List<TitleSearchResponse> titles, ShelfKind kind) {
		this(reason, titles, kind, false);
	}

	public enum ShelfKind { PER_SEED, FINISHED_SEED, MORE_PICKS, GENRE_PROFILE, NEW_RELEASES, HIDDEN_GEMS, TRENDING, PERSON, KEYWORD }
}
