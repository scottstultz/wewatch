package com.wewatch.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record BulkSeasonRequest(
	@NotNull Boolean watched,
	@NotEmpty List<Integer> episodeNumbers
) {
}
