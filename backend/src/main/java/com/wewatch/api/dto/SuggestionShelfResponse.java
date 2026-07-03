package com.wewatch.api.dto;

import java.util.List;

public record SuggestionShelfResponse(
	String reason,
	List<TitleSearchResponse> titles,
	ShelfKind kind
) {
	public enum ShelfKind { PER_SEED, FINISHED_SEED, GENRE_PROFILE, NEW_RELEASES, HIDDEN_GEMS, TRENDING }
}
