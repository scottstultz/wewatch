package com.wewatch.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
	@NotBlank @Email @Size(max = 255)
	@Schema(description = "Must already be present in the allowlist, or registration is rejected")
	String email,
	@NotBlank @Size(max = 255) String displayName,
	@NotBlank @Size(min = 8, max = 72) String password
) {}
