package com.wewatch.api.dto;

import jakarta.validation.constraints.NotNull;

import com.wewatch.api.model.WatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchlistEntryCreateRequest(
	@NotNull
	@Schema(description = "ID of the WeWatch title (not the TMDB external ID) to add")
	Long titleId,

	@Schema(description = "Initial watch status; defaults to WANT_TO_WATCH if omitted",
		allowableValues = {"WANT_TO_WATCH", "WATCHING", "WATCHED"})
	WatchStatus status
) {
}
