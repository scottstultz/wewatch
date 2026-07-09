package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// One streaming service for the settings picker and availability badges (#270).
// Data is TMDB's JustWatch-licensed provider catalog — the frontend must show
// JustWatch attribution wherever these names/logos render.
public record WatchProviderResponse(
	@Schema(description = "TMDB watch-provider id")
	int id,
	String name,
	@Schema(description = "Absolute URL to the provider's logo image")
	String logoUrl,
	@Schema(description = "TMDB/JustWatch display ranking; lower sorts first")
	int displayPriority
) {
}
