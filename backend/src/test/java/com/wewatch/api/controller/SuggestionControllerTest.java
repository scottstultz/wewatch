package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.SuggestionDismissalService;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(SuggestionController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class SuggestionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private SuggestionService suggestionService;

	@MockBean
	private SuggestionDismissalService suggestionDismissalService;

	@MockBean
	private WatchlistService watchlistService;

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
	void getSuggestionsReturnsShelves() throws Exception {
		TitleSearchResponse title = new TitleSearchResponse(
			"1234", "TMDB", TitleType.TV, "Severance", "Employees discover the dark truth.", LocalDate.of(2022, 2, 18), null, null);
		SuggestionShelfResponse shelf = new SuggestionShelfResponse("Because you added The Bear", List.of(title), SuggestionShelfResponse.ShelfKind.PER_SEED);

		when(suggestionService.topPicks(42L)).thenReturn(List.of(shelf));

		mockMvc.perform(get("/api/suggestions?watchlistId=42")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].reason").value("Because you added The Bear"))
			.andExpect(jsonPath("$[0].kind").value("PER_SEED"))
			.andExpect(jsonPath("$[0].titles[0].name").value("Severance"))
			.andExpect(jsonPath("$[0].titles[0].externalId").value("1234"));
	}

	@Test
	void getSuggestionsReturnsEmptyListWhenNoResults() throws Exception {
		when(suggestionService.topPicks(42L)).thenReturn(List.of());

		mockMvc.perform(get("/api/suggestions?watchlistId=42")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getSuggestionsReturnsForbiddenForNonMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(42L, 1L);

		mockMvc.perform(get("/api/suggestions?watchlistId=42")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isForbidden());
	}

	@Test
	void getSuggestionsRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/suggestions?watchlistId=42"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void dismissRecordsForTheCallerAndEvictsTheirCachedShelves() throws Exception {
		mockMvc.perform(post("/api/suggestions/dismissals")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"tmdbId\": \"1234\"}"))
			.andExpect(status().isNoContent());

		verify(suggestionDismissalService).dismiss(1L, "1234");
		verify(suggestionService).evictForUser(1L);
	}

	@Test
	void dismissRejectsABlankTmdbId() throws Exception {
		mockMvc.perform(post("/api/suggestions/dismissals")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"tmdbId\": \"\"}"))
			.andExpect(status().isBadRequest());

		verify(suggestionDismissalService, never()).dismiss(any(), anyString());
	}

	@Test
	void dismissRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/suggestions/dismissals")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"tmdbId\": \"1234\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void undoDismissalDeletesForTheCallerAndEvictsTheirCachedShelves() throws Exception {
		mockMvc.perform(delete("/api/suggestions/dismissals/1234")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isNoContent());

		verify(suggestionDismissalService).undismiss(1L, "1234");
		verify(suggestionService).evictForUser(1L);
	}

	@Test
	void undoDismissalRequiresAuthentication() throws Exception {
		mockMvc.perform(delete("/api/suggestions/dismissals/1234"))
			.andExpect(status().isUnauthorized());
	}
}
