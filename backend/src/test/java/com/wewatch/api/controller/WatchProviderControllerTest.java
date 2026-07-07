package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.dto.WatchRegionResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchProviderService;

@WebMvcTest(WatchProviderController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class WatchProviderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private WatchProviderService watchProviderService;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	private static final User TEST_USER = new User(1L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");

	private static final Jwt TEST_JWT = Jwt.withTokenValue("test-token")
		.header("alg", "HS256")
		.claim("sub", "1")
		.issuer("wewatch")
		.issuedAt(Instant.EPOCH)
		.expiresAt(Instant.EPOCH.plusSeconds(86400))
		.build();

	@BeforeEach
	void setupAuth() {
		when(jwtDecoder.decode(any())).thenReturn(TEST_JWT);
		when(userService.findById(1L)).thenReturn(TEST_USER);
	}

	@Test
	void getProvidersReturnsRegionCatalog() throws Exception {
		when(watchProviderService.providersForRegion("US")).thenReturn(List.of(
			new WatchProviderResponse(8, "Netflix", "https://image.tmdb.org/t/p/w92/n.jpg", 0)));

		mockMvc.perform(get("/api/watch-providers")
			.header("Authorization", "Bearer test-token")
			.param("region", "US"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(8))
			.andExpect(jsonPath("$[0].name").value("Netflix"))
			.andExpect(jsonPath("$[0].logoUrl").value("https://image.tmdb.org/t/p/w92/n.jpg"));
	}

	@Test
	void getProvidersRejectsMalformedRegion() throws Exception {
		mockMvc.perform(get("/api/watch-providers")
			.header("Authorization", "Bearer test-token")
			.param("region", "usa"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void getRegionsReturnsRegionList() throws Exception {
		when(watchProviderService.regions()).thenReturn(List.of(
			new WatchRegionResponse("CA", "Canada"),
			new WatchRegionResponse("US", "United States")));

		mockMvc.perform(get("/api/watch-providers/regions")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].code").value("CA"));
	}

	@Test
	void endpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/watch-providers").param("region", "US"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/watch-providers/regions"))
			.andExpect(status().isUnauthorized());
	}
}
