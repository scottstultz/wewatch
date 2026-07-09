package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record BulkSeasonRequest(
	@NotNull
	@Schema(description = "Target watched state to apply to every episode number listed")
	Boolean watched,
	@NotEmpty
	@Schema(description = "Episode numbers within the season to update. When watched=true, "
		+ "any episode numbers that have not aired yet are silently skipped.")
	List<Integer> episodeNumbers
) {
}
