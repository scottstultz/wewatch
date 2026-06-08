package com.wewatch.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
	@NotBlank String provider,
	@NotBlank String credential
) {}
