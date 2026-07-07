package com.wewatch.api.dto;

// One streaming service for the settings picker and availability badges (#270).
// Data is TMDB's JustWatch-licensed provider catalog — the frontend must show
// JustWatch attribution wherever these names/logos render.
public record WatchProviderResponse(
	int id,
	String name,
	String logoUrl,
	int displayPriority
) {
}
