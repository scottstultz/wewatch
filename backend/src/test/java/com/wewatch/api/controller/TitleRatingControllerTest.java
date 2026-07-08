package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.TitleRatingService;
import com.wewatch.api.service.UserService;

@WebMvcTest(TitleRatingController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class TitleRatingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private TitleRatingService titleRatingService;

	@MockBean
	private SuggestionService suggestionService;

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
	void rateRecordsForTheCallerAndEvictsTheirCachedShelves() throws Exception {
		mockMvc.perform(put("/api/titles/20/rating")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\": \"UP\"}"))
			.andExpect(status().isNoContent());

		verify(titleRatingService).rate(1L, 20L, Rating.UP);
		verify(suggestionService).evictForUser(1L);
	}

	@Test
	void rateRejectsAMissingRatingValue() throws Exception {
		mockMvc.perform(put("/api/titles/20/rating")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest());

		verify(titleRatingService, never()).rate(any(), any(), any());
	}

	@Test
	void ratingAMissingTitleReturnsNotFound() throws Exception {
		doThrow(new NoSuchElementException("Title not found: 20"))
			.when(titleRatingService).rate(1L, 20L, Rating.DOWN);

		mockMvc.perform(put("/api/titles/20/rating")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\": \"DOWN\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void clearRatingDeletesAndEvicts() throws Exception {
		mockMvc.perform(delete("/api/titles/20/rating")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isNoContent());

		verify(titleRatingService).unrate(1L, 20L);
		verify(suggestionService).evictForUser(1L);
	}

	@Test
	void rateRequiresAuthentication() throws Exception {
		mockMvc.perform(put("/api/titles/20/rating")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\": \"UP\"}"))
			.andExpect(status().isUnauthorized());
	}
}
