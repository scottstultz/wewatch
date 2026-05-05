package com.wewatch.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
	@Email
	@Size(max = 255)
	String email,

	@Size(max = 255)
	String displayName
) {
}
