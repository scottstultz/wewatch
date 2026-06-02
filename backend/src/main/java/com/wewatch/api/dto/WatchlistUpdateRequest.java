package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WatchlistUpdateRequest(
	@NotBlank
	@Size(max = 255)
	String name
) {
}
