package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
	@Schema(description = "Self-issued WeWatch JWT (HS256). Pass as \"Bearer {token}\" in the Authorization header.")
	String token
) {}
