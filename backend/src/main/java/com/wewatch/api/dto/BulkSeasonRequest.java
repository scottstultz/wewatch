package com.wewatch.api.dto;

import jakarta.validation.constraints.NotNull;

public record BulkSeasonRequest(
	@NotNull Boolean watched
) {
}
