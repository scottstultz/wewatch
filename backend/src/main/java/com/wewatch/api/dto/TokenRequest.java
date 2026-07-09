package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
	@NotBlank
	@Schema(description = "Credential provider", allowableValues = {"google", "email"})
	String provider,
	@NotBlank
	@Schema(description = "For provider=\"google\": the Google ID token. "
		+ "For provider=\"email\": a JSON string, e.g. {\"email\":\"user@example.com\",\"password\":\"...\"}")
	String credential
) {}
