package com.wewatch.api.dto;

import java.util.List;

public record SuggestionShelfResponse(
	String reason,
	List<TitleSearchResponse> titles
) {}
