package com.wewatch.api.dto;

import com.wewatch.api.model.MemberRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
	@NotNull MemberRole role
) {
}
