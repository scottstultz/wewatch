package com.wewatch.api.dto;

import com.wewatch.api.model.MemberRole;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

public record UpdateMemberRoleRequest(
	@NotNull
	@Schema(description = "Role to assign the target member. OWNER is not assignable — promoting a member "
		+ "to owner is not supported.", allowableValues = {"EDITOR", "VIEWER"})
	MemberRole role
) {
}
