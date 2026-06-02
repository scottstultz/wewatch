package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WatchlistCreateRequest(
	@NotBlank
	@Size(max = 255)
	String name
) {
}
