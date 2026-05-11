package com.wewatch.api.dto;

import com.wewatch.api.model.WatchStatus;

public record WatchlistEntryUpdateRequest(
	WatchStatus status
) {
}
