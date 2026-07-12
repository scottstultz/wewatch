package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

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

import com.wewatch.api.dto.StatsResponse;
import com.wewatch.api.dto.StatsResponse.GenreStat;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.StatsService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(StatsController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class StatsControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private StatsService statsService;

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

	private static final StatsResponse STATS = new StatsResponse(
		12, 3, 248, 14_820, 1_440, 13_380, 2,
		List.of(
			new GenreStat(18, "Drama", 9_100, 7),
			new GenreStat(35, "Comedy", 4_200, 5)
		)
	);

	@BeforeEach
	void setUpMocks() {
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	// ─── GET /api/watchlists/{watchlistId}/stats ─────────────────────────────

	@Test
	void returnsStatsForTheWatchlist() throws Exception {
		when(statsService.statsFor(10L)).thenReturn(STATS);

		mockMvc.perform(
			get("/api/watchlists/10/stats")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moviesFinished").value(12))
			.andExpect(jsonPath("$.showsFinished").value(3))
			.andExpect(jsonPath("$.episodesFinished").value(248))
			.andExpect(jsonPath("$.totalMinutes").value(14820))
			.andExpect(jsonPath("$.movieMinutes").value(1440))
			.andExpect(jsonPath("$.episodeMinutes").value(13380))
			.andExpect(jsonPath("$.itemsMissingRuntime").value(2))
			.andExpect(jsonPath("$.genres.length()").value(2))
			.andExpect(jsonPath("$.genres[0].name").value("Drama"))
			.andExpect(jsonPath("$.genres[0].minutes").value(9100))
			.andExpect(jsonPath("$.genres[0].titleCount").value(7));
	}

	@Test
	void returnsZeroesForAnEmptyWatchlist() throws Exception {
		when(statsService.statsFor(10L))
			.thenReturn(new StatsResponse(0, 0, 0, 0, 0, 0, 0, List.of()));

		mockMvc.perform(
			get("/api/watchlists/10/stats")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalMinutes").value(0))
			.andExpect(jsonPath("$.genres.length()").value(0));
	}

	@Test
	void returnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(
			get("/api/watchlists/10/stats")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void returnsNotFoundForAnUnknownWatchlist() throws Exception {
		doThrow(new NoSuchElementException("Watchlist not found"))
			.when(watchlistService).requireMember(99L, 10L);

		mockMvc.perform(
			get("/api/watchlists/99/stats")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isNotFound());
	}

	@Test
	void returnsUnauthorizedWithoutAToken() throws Exception {
		mockMvc.perform(get("/api/watchlists/10/stats"))
			.andExpect(status().isUnauthorized());
	}
}
