package com.wewatch.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.controller.UserController;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.UserService;

/**
 * The <em>dev</em> half of the per-profile docs expectation (#297, #343): under the local
 * profile — the only one that sets springdoc.api-docs.enabled=true — the OpenAPI spec/Swagger UI
 * paths must be reachable without a token, while unrelated endpoints still require one. The
 * disabled default and prod configurations are covered by {@link OpenApiDisabledByDefaultTest}
 * and {@link OpenApiProdSecurityTest}. This is a security-slice test, not a springdoc smoke
 * test — springdoc's auto-configured resource controllers aren't loaded under @WebMvcTest, so an
 * unmapped path here surfaces as 404 rather than 200; either way it proves the path isn't
 * rejected by Spring Security. End-to-end serving is verified manually (see docs/api.md).
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class OpenApiSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UserService userService;

	@MockBean
	private AllowedEmailRepository allowedEmailRepository;

	@MockBean
	private SuggestionService suggestionService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	@Test
	void apiDocsAreReachableWithoutAToken() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(result -> assertThat(result.getResponse().getStatus())
				.isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
	}

	@Test
	void yamlSpecIsReachableWithoutAToken() throws Exception {
		// A sibling path, not under /v3/api-docs/** — it needs its own matcher.
		mockMvc.perform(get("/v3/api-docs.yaml"))
			.andExpect(result -> assertThat(result.getResponse().getStatus())
				.isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
	}

	@Test
	void swaggerUiIsReachableWithoutAToken() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(result -> assertThat(result.getResponse().getStatus())
				.isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
	}

	@Test
	void unrelatedEndpointsStillRequireAToken() throws Exception {
		mockMvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized());
	}

}
