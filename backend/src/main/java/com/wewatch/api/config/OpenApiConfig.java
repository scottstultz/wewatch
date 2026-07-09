package com.wewatch.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
	info = @Info(
		title = "WeWatch API",
		description = "REST API for tracking movies and TV shows, managing shared watchlists, "
			+ "and generating personalized suggestions. See docs/architecture.md in the repo "
			+ "for design rationale behind auth, TMDB caching, and the suggestion pipeline.",
		version = "v1"
	),
	security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
	name = "bearerAuth",
	type = SecuritySchemeType.HTTP,
	scheme = "bearer",
	bearerFormat = "JWT",
	description = "Self-issued WeWatch JWT, obtained from POST /api/auth/token or POST /api/auth/register."
)
public class OpenApiConfig {
}
