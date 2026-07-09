package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchRegionResponse(
	@Schema(description = "Two-letter ISO 3166-1 region code, e.g. \"US\"")
	String code,
	String name
) {
}
