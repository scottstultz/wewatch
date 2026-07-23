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

import com.wewatch.api.dto.TonightPickResponse;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.TonightService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(TonightController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class TonightControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private TonightService tonightService;

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

	@BeforeEach
	void setUpMocks() {
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	// ─── GET /api/watchlists/{watchlistId}/tonight ───────────────────────────

	@Test
	void returnsTheEntriesThatFitTheWindow() throws Exception {
		when(tonightService.fitsWithin(10L, 60)).thenReturn(List.of(
			new TonightPickResponse(2L, TitleType.TV, 47, 3, 7),
			new TonightPickResponse(1L, TitleType.MOVIE, 58, null, null)
		));

		mockMvc.perform(
			get("/api/watchlists/10/tonight?maxMinutes=60")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].entryId").value(2))
			.andExpect(jsonPath("$[0].type").value("TV"))
			.andExpect(jsonPath("$[0].runtimeMinutes").value(47))
			.andExpect(jsonPath("$[0].nextSeason").value(3))
			.andExpect(jsonPath("$[0].nextEpisode").value(7))
			.andExpect(jsonPath("$[1].entryId").value(1))
			.andExpect(jsonPath("$[1].type").value("MOVIE"))
			.andExpect(jsonPath("$[1].nextSeason").doesNotExist());

		verify(tonightService).fitsWithin(10L, 60);
	}

	@Test
	void returnsEmptyListWhenNothingFits() throws Exception {
		when(tonightService.fitsWithin(10L, 30)).thenReturn(List.of());

		mockMvc.perform(
			get("/api/watchlists/10/tonight?maxMinutes=30")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void returnsBadRequestForAnOutOfRangeWindow() throws Exception {
		when(tonightService.fitsWithin(10L, 0))
			.thenThrow(new IllegalArgumentException("maxMinutes must be between 1 and 600, got 0"));

		mockMvc.perform(
			get("/api/watchlists/10/tonight?maxMinutes=0")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("maxMinutes must be between 1 and 600, got 0"));
	}

	@Test
	void returnsBadRequestWhenTheWindowIsMissing() throws Exception {
		mockMvc.perform(
			get("/api/watchlists/10/tonight")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isBadRequest());
	}

	@Test
	void returnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(
			get("/api/watchlists/10/tonight?maxMinutes=60")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}
}
