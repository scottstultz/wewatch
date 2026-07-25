package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.wewatch.api.service.GenreBrowseService;
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
	private GenreBrowseService genreBrowseService;

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

	// ── Genre browse (#384) ──────────────────────────────────

	@Test
	void browseReturnsTheRankedPageForTheSelectedGenres() throws Exception {
		TitleSearchResponse title = new TitleSearchResponse("603", "TMDB", TitleType.MOVIE, "The Matrix",
			"A hacker learns the truth.", LocalDate.of(1999, 3, 30), null, List.of(28, 878), List.of(8));
		when(genreBrowseService.browse(42L, TitleType.MOVIE, List.of(10749, 35), 1))
			.thenReturn(List.of(title));

		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE&genres=10749,35")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].externalId").value("603"))
			.andExpect(jsonPath("$[0].name").value("The Matrix"))
			.andExpect(jsonPath("$[0].type").value("MOVIE"))
			// Badged, not provider-filtered — the badge has to reach the client or
			// the tile can't show it
			.andExpect(jsonPath("$[0].providerIds[0]").value(8));
	}

	@Test
	void browseDefaultsToTheFirstPage() throws Exception {
		when(genreBrowseService.browse(any(), any(), any(), anyInt())).thenReturn(List.of());

		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=TV&genres=35")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk());

		verify(genreBrowseService).browse(42L, TitleType.TV, List.of(35), 1);
	}

	@Test
	void browsePassesTheRequestedPageThrough() throws Exception {
		when(genreBrowseService.browse(any(), any(), any(), anyInt())).thenReturn(List.of());

		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE&genres=35&page=3")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk());

		verify(genreBrowseService).browse(42L, TitleType.MOVIE, List.of(35), 3);
	}

	@Test
	void browseReturnsBadRequestWhenTheServiceRejectsThePage() throws Exception {
		when(genreBrowseService.browse(42L, TitleType.MOVIE, List.of(35), 7))
			.thenThrow(new IllegalArgumentException("page must be between 1 and 6"));

		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE&genres=35&page=7")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void browseReturnsBadRequestWhenGenresAreMissing() throws Exception {
		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest());

		verify(genreBrowseService, never()).browse(any(), any(), any(), anyInt());
	}

	// A lowercase {type} path variable would miss the case-sensitive TitleType
	// binding, which is why type is a query param (#358). Guard the binding itself.
	@Test
	void browseReturnsBadRequestForAnUnknownType() throws Exception {
		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=movie&genres=35")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest());

		verify(genreBrowseService, never()).browse(any(), any(), any(), anyInt());
	}

	@Test
	void browseReturnsForbiddenForNonMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(42L, 1L);

		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE&genres=35")
				.header("Authorization", "Bearer test-token"))
			.andExpect(status().isForbidden());

		// Membership is checked before the feed is built, so a non-member never
		// reaches TMDB
		verify(genreBrowseService, never()).browse(any(), any(), any(), anyInt());
	}

	@Test
	void browseRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/suggestions/browse?watchlistId=42&type=MOVIE&genres=35"))
			.andExpect(status().isUnauthorized());
	}
}
