package com.wewatch.api.dto;

import jakarta.validation.constraints.NotNull;

import com.wewatch.api.model.WatchStatus;

public record WatchlistEntryCreateRequest(
	@NotNull
	Long titleId,

	WatchStatus status
) {
}
