package com.wewatch.api.dto;

import com.wewatch.api.model.WatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchlistEntryUpdateRequest(
	@Schema(description = "New watch status for the entry", allowableValues = {"WANT_TO_WATCH", "WATCHING", "WATCHED"})
	WatchStatus status
) {
}
