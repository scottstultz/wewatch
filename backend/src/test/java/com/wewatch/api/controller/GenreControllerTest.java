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

import com.wewatch.api.dto.GenreResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.GenreCatalogService;
import com.wewatch.api.service.UserService;

@WebMvcTest(GenreController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class GenreControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private GenreCatalogService genreCatalogService;

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
	void getGenresReturnsBothCatalogsSeparately() throws Exception {
		// The two catalogs are kept apart on purpose: movie 28 "Action" and TV 10759 "Action &
		// Adventure" are distinct genres, and a merged list would offer both unlabelled.
		when(genreCatalogService.genresFor(TitleType.MOVIE)).thenReturn(List.of(
			new GenreResponse(28, "Action"),
			new GenreResponse(18, "Drama")));
		when(genreCatalogService.genresFor(TitleType.TV)).thenReturn(List.of(
			new GenreResponse(10759, "Action & Adventure"),
			new GenreResponse(18, "Drama")));

		mockMvc.perform(get("/api/genres").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.movie.length()").value(2))
			.andExpect(jsonPath("$.movie[0].id").value(28))
			.andExpect(jsonPath("$.movie[0].name").value("Action"))
			.andExpect(jsonPath("$.tv.length()").value(2))
			.andExpect(jsonPath("$.tv[0].id").value(10759))
			.andExpect(jsonPath("$.tv[0].name").value("Action & Adventure"));
	}

	@Test
	void getGenresReturnsEmptyListsRatherThanFailingWhenTmdbIsDown() throws Exception {
		// GenreCatalogService swallows a TMDB outage and yields empty lists, so unlike
		// /api/watch-providers this endpoint has no 502: a caller loses its genre labels, not
		// the page around them.
		when(genreCatalogService.genresFor(any())).thenReturn(List.of());

		mockMvc.perform(get("/api/genres").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.movie.length()").value(0))
			.andExpect(jsonPath("$.tv.length()").value(0));
	}

	@Test
	void getGenresRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/genres"))
			.andExpect(status().isUnauthorized());
	}
}
