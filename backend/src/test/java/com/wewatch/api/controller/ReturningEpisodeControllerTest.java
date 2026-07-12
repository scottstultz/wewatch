package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wewatch.api.dto.ReturningEpisodeResponse;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.ReturningEpisodeService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(ReturningEpisodeController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class ReturningEpisodeControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private ReturningEpisodeService returningEpisodeService;

	@MockBean
	private WatchlistService watchlistService;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	private static final User TEST_USER = new User(
		10L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123"
	);

	private static final ReturningEpisodeResponse SEVERANCE = new ReturningEpisodeResponse(
		1L, "95396", "tmdb", "Severance", "https://img/severance.jpg",
		2, 4, "Woe's Hollow", LocalDate.of(2026, 7, 17), 52
	);

	@BeforeEach
	void setUpMocks() {
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	// ─── GET /api/watchlists/{watchlistId}/returning ─────────────────────────

	@Test
	void returnsUpcomingEpisodesForTheWatchlist() throws Exception {
		when(returningEpisodeService.returningWithin(10L, 7)).thenReturn(List.of(SEVERANCE));

		mockMvc.perform(
			get("/api/watchlists/10/returning")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].entryId").value(1))
			.andExpect(jsonPath("$[0].showName").value("Severance"))
			.andExpect(jsonPath("$[0].seasonNumber").value(2))
			.andExpect(jsonPath("$[0].episodeNumber").value(4))
			.andExpect(jsonPath("$[0].airDate").value("2026-07-17"));
	}

	@Test
	void defaultsToASevenDayWindow() throws Exception {
		when(returningEpisodeService.returningWithin(any(), any(Integer.class)))
			.thenReturn(List.of());

		mockMvc.perform(
			get("/api/watchlists/10/returning")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk());

		verify(returningEpisodeService).returningWithin(10L, 7);
	}

	@Test
	void honoursAnExplicitDayCount() throws Exception {
		when(returningEpisodeService.returningWithin(10L, 14)).thenReturn(List.of());

		mockMvc.perform(
			get("/api/watchlists/10/returning?days=14")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk());

		verify(returningEpisodeService).returningWithin(10L, 14);
	}

	@Test
	void returnsEmptyListWhenNothingIsAiringSoon() throws Exception {
		when(returningEpisodeService.returningWithin(10L, 7)).thenReturn(List.of());

		mockMvc.perform(
			get("/api/watchlists/10/returning")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void returnsBadRequestForAnOutOfRangeDayCount() throws Exception {
		when(returningEpisodeService.returningWithin(10L, 90))
			.thenThrow(new IllegalArgumentException("days must be between 1 and 30, got 90"));

		mockMvc.perform(
			get("/api/watchlists/10/returning?days=90")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("days must be between 1 and 30, got 90"));
	}

	@Test
	void returnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(
			get("/api/watchlists/10/returning")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void returnsUnauthorizedWithoutAToken() throws Exception {
		mockMvc.perform(get("/api/watchlists/10/returning"))
			.andExpect(status().isUnauthorized());
	}
}
