package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.EpisodeProgressService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(EpisodeProgressController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class EpisodeProgressControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private EpisodeProgressService episodeProgressService;

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

	private static final Instant EPOCH = Instant.EPOCH;

	@BeforeEach
	void setUpMocks() {
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
		when(watchlistService.requireEditor(any(), any())).thenReturn(null);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	// ─── GET /api/watchlists/{wId}/entries/{eId}/episodes ────────────────────

	@Test
	void getProgressReturnsListForEntry() throws Exception {
		EpisodeProgress ep1 = new EpisodeProgress(1L, 1L, 1, 1, true, EPOCH);
		EpisodeProgress ep2 = new EpisodeProgress(2L, 1L, 1, 2, false, null);
		when(episodeProgressService.getProgress(10L, 1L, null)).thenReturn(List.of(ep1, ep2));

		mockMvc.perform(
			get("/api/watchlists/10/entries/1/episodes")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].seasonNumber").value(1))
			.andExpect(jsonPath("$[0].episodeNumber").value(1))
			.andExpect(jsonPath("$[0].watched").value(true))
			.andExpect(jsonPath("$[1].watched").value(false));
	}

	@Test
	void getProgressFiltersBySeason() throws Exception {
		EpisodeProgress ep = new EpisodeProgress(3L, 1L, 2, 1, true, EPOCH);
		when(episodeProgressService.getProgress(10L, 1L, 2)).thenReturn(List.of(ep));

		mockMvc.perform(
			get("/api/watchlists/10/entries/1/episodes?season=2")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].seasonNumber").value(2));
	}

	@Test
	void getProgressReturnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(
			get("/api/watchlists/10/entries/1/episodes")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void getProgressReturnsBadRequestForMovie() throws Exception {
		when(episodeProgressService.getProgress(10L, 2L, null))
			.thenThrow(new IllegalArgumentException("Episode tracking is only available for TV shows"));

		mockMvc.perform(
			get("/api/watchlists/10/entries/2/episodes")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Episode tracking is only available for TV shows"));
	}

	// ─── PATCH /api/watchlists/{wId}/entries/{eId}/episodes/{s}/{e} ──────────

	@Test
	void toggleEpisodeReturnsUpdatedProgress() throws Exception {
		EpisodeProgress toggled = new EpisodeProgress(5L, 1L, 1, 3, true, EPOCH);
		when(episodeProgressService.toggleEpisode(10L, 1L, 1, 3)).thenReturn(toggled);

		mockMvc.perform(
			patch("/api/watchlists/10/entries/1/episodes/1/3")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(5))
			.andExpect(jsonPath("$.seasonNumber").value(1))
			.andExpect(jsonPath("$.episodeNumber").value(3))
			.andExpect(jsonPath("$.watched").value(true));
	}

	@Test
	void toggleEpisodeReturnsForbiddenWhenViewer() throws Exception {
		doThrow(new ForbiddenException("Editor role required"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			patch("/api/watchlists/10/entries/1/episodes/1/3")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Editor role required"));
	}

	@Test
	void toggleEpisodeReturnsBadRequestForMovie() throws Exception {
		when(episodeProgressService.toggleEpisode(10L, 2L, 1, 1))
			.thenThrow(new IllegalArgumentException("Episode tracking is only available for TV shows"));

		mockMvc.perform(
			patch("/api/watchlists/10/entries/2/episodes/1/1")
				.with(asUser(TEST_USER))
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Episode tracking is only available for TV shows"));
	}

	// ─── PUT /api/watchlists/{wId}/entries/{eId}/episodes/{s} ────────────────

	@Test
	void bulkMarkSeasonReturnsUpdatedList() throws Exception {
		EpisodeProgress ep1 = new EpisodeProgress(1L, 1L, 1, 1, true, EPOCH);
		EpisodeProgress ep2 = new EpisodeProgress(2L, 1L, 1, 2, true, EPOCH);
		when(episodeProgressService.bulkMarkSeason(10L, 1L, 1, true, List.of(1, 2)))
			.thenReturn(List.of(ep1, ep2));

		mockMvc.perform(
			put("/api/watchlists/10/entries/1/episodes/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "watched": true,
					  "episodeNumbers": [1, 2]
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].watched").value(true))
			.andExpect(jsonPath("$[1].watched").value(true));
	}

	@Test
	void bulkMarkSeasonReturnsForbiddenWhenViewer() throws Exception {
		doThrow(new ForbiddenException("Editor role required"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			put("/api/watchlists/10/entries/1/episodes/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "watched": true,
					  "episodeNumbers": [1, 2]
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Editor role required"));
	}

	@Test
	void bulkMarkSeasonReturnsBadRequestForMovie() throws Exception {
		when(episodeProgressService.bulkMarkSeason(10L, 2L, 1, true, List.of(1, 2)))
			.thenThrow(new IllegalArgumentException("Episode tracking is only available for TV shows"));

		mockMvc.perform(
			put("/api/watchlists/10/entries/2/episodes/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "watched": true,
					  "episodeNumbers": [1, 2]
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Episode tracking is only available for TV shows"));
	}
}
