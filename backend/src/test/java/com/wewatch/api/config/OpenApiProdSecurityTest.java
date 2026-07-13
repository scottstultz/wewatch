package com.wewatch.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
 * The prod half of the per-profile docs expectation (#343): application-prod.properties keeps
 * an explicit springdoc disable, and these paths must not be anonymously reachable under it.
 * Running the slice with the prod profile works because @WebMvcTest never resolves the prod
 * datasource/JWT placeholders — property placeholders resolve only on injection, and every bean
 * that would inject them is mocked here. See {@link OpenApiDisabledByDefaultTest} for the
 * stronger fail-closed guard on the no-profile default.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("prod")
class OpenApiProdSecurityTest {

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
	void apiDocsRequireATokenInProd() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
	}

	@Test
	void swaggerUiRequiresATokenInProd() throws Exception {
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
	}

}
