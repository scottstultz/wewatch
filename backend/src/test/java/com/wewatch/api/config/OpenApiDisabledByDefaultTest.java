package com.wewatch.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.controller.UserController;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.UserService;

/**
 * The fail-closed half of #343: with no profile active — deliberately the only test in the
 * suite without @ActiveProfiles — the shipped default in application.properties must leave the
 * OpenAPI/Swagger paths behind authentication. This is the test that fails against the pre-#343
 * unconditional permitAll, and it exercises the base-properties default directly: a typo there
 * would silently reopen the docs for any profile that forgets to disable them (springdoc's own
 * default is enabled) without failing any other test. With the matchers gone the paths fall
 * through to anyRequest().authenticated(), so they answer 401 — which is also what makes this
 * assertable under @WebMvcTest at all, since springdoc's controllers never load in the slice.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class OpenApiDisabledByDefaultTest {

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
	void apiDocsRequireAToken() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
	}

	@Test
	void swaggerUiRequiresAToken() throws Exception {
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	void publicEndpointsStayPublic() throws Exception {
		// The gate must remove only the docs matchers, not the health/auth permitAll.
		mockMvc.perform(get("/api/health")).andExpect(status().isNotFound());
	}

}
