package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wewatch.api.exception.DuplicateWatchlistEntryException;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistEntryService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(WatchlistEntryController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class WatchlistEntryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private WatchlistEntryService watchlistEntryService;

	@MockBean
	private TitleService titleService;

	@MockBean
	private UserService userService;

	@MockBean
	private WatchlistService watchlistService;

	@MockBean
	private EpisodeProgressRepository episodeProgressRepository;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	private static final User TEST_USER = new User(10L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");

	private static final Title TEST_TITLE = new Title(20L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, "/poster.jpg", Instant.EPOCH, Instant.EPOCH);

	@BeforeEach
	void setUpMocks() {
		when(titleService.findById(20L)).thenReturn(TEST_TITLE);
		when(titleService.findByIds(any())).thenReturn(Map.of(20L, TEST_TITLE));
		// Default: caller is a member/editor — individual tests override this to test 403/404
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
		when(watchlistService.requireEditor(any(), any())).thenReturn(null);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	@Test
	void createWatchlistEntryReturnsCreatedEntry() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry createdEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);
		createdEntry.setExternalId("603");
		createdEntry.setExternalSource("TMDB");

		when(watchlistEntryService.create(any(WatchlistEntry.class))).thenReturn(createdEntry);

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20,
					  "status": "WANT_TO_WATCH"
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/watchlists/10/entries/1"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.watchlistId").value(10))
			.andExpect(jsonPath("$.titleId").value(20))
			.andExpect(jsonPath("$.status").value("WANT_TO_WATCH"))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.type").value("MOVIE"))
			.andExpect(jsonPath("$.posterUrl").value("/poster.jpg"))
			.andExpect(jsonPath("$.addedAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).create(any(WatchlistEntry.class));
	}

	@Test
	void createWatchlistEntryAllowsMissingStatus() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry createdEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);

		when(watchlistEntryService.create(any(WatchlistEntry.class))).thenReturn(createdEntry);

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("WANT_TO_WATCH"));
	}

	@Test
	void createWatchlistEntryReturnsBadRequestWhenTitleIdMissing() throws Exception {
		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WANT_TO_WATCH"
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void createWatchlistEntryReturnsNotFoundWhenWatchlistMissing() throws Exception {
		doThrow(new NoSuchElementException("Watchlist not found: 10"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist not found: 10"));
	}

	@Test
	void createWatchlistEntryReturnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void createWatchlistEntryReturnsForbiddenWhenViewer() throws Exception {
		doThrow(new ForbiddenException("Editor role required"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Editor role required"));
	}

	@Test
	void createWatchlistEntryReturnsNotFoundWhenTitleMissing() throws Exception {
		when(watchlistEntryService.create(any(WatchlistEntry.class)))
			.thenThrow(new NoSuchElementException("Title not found: 20"));

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Title not found: 20"));
	}

	@Test
	void createWatchlistEntryReturnsConflictWhenDuplicate() throws Exception {
		when(watchlistEntryService.create(any(WatchlistEntry.class)))
			.thenThrow(new DuplicateWatchlistEntryException(10L, 20L));

		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Watchlist entry already exists for watchlist 10 and title 20"));
	}

	@Test
	void createWatchlistEntryReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(
			post("/api/watchlists/10/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isUnauthorized());
	}

	@Test
	void getWatchlistEntriesReturnsEntriesForWatchlist() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);

		when(watchlistEntryService.findByFilters(eq(10L), isNull(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(entry)));

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].watchlistId").value(10))
			.andExpect(jsonPath("$.content[0].titleId").value(20))
			.andExpect(jsonPath("$.content[0].status").value("WANT_TO_WATCH"))
			.andExpect(jsonPath("$.content[0].addedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).findByFilters(eq(10L), isNull(), any(Pageable.class));
	}

	@Test
	void getWatchlistEntriesReturnsEmptyListWhenNoEntriesExist() throws Exception {
		when(watchlistEntryService.findByFilters(eq(10L), isNull(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of()));

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getWatchlistEntriesFiltersByStatus() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WATCHING, addedAt, addedAt, addedAt, null
		);

		when(watchlistEntryService.findByFilters(eq(10L), eq(WatchStatus.WATCHING), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(entry)));

		mockMvc.perform(get("/api/watchlists/10/entries")
			.with(asUser(TEST_USER))
			.param("status", "WATCHING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].status").value("WATCHING"))
			.andExpect(jsonPath("$.content[0].startedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).findByFilters(eq(10L), eq(WatchStatus.WATCHING), any(Pageable.class));
	}

	@Test
	void getWatchlistEntriesReturnsNotFoundWhenWatchlistMissing() throws Exception {
		doThrow(new NoSuchElementException("Watchlist not found: 10"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist not found: 10"));
	}

	@Test
	void getWatchlistEntriesReturnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(10L, 10L);

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void getWatchlistEntriesReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/watchlists/10/entries"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void getWatchlistEntryReturnsEntry() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);

		when(watchlistEntryService.findById(10L, 1L)).thenReturn(entry);

		mockMvc.perform(get("/api/watchlists/10/entries/1").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.watchlistId").value(10))
			.andExpect(jsonPath("$.status").value("WANT_TO_WATCH"));

		verify(watchlistEntryService).findById(10L, 1L);
	}

	@Test
	void getWatchlistEntryReturnsNotFoundWhenEntryMissing() throws Exception {
		when(watchlistEntryService.findById(10L, 1L))
			.thenThrow(new NoSuchElementException("Watchlist entry not found: 1"));

		mockMvc.perform(get("/api/watchlists/10/entries/1").with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist entry not found: 1"));
	}

	@Test
	void updateWatchlistEntryReturnsUpdatedEntry() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-28T13:00:00Z");
		WatchlistEntry updatedEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WATCHING, addedAt, updatedAt, updatedAt, null
		);

		when(watchlistEntryService.update(eq(10L), eq(1L), any(WatchlistEntry.class))).thenReturn(updatedEntry);

		mockMvc.perform(
			patch("/api/watchlists/10/entries/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WATCHING"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.watchlistId").value(10))
			.andExpect(jsonPath("$.status").value("WATCHING"))
			.andExpect(jsonPath("$.startedAt").value("2026-04-28T13:00:00Z"));

		verify(watchlistEntryService).update(eq(10L), eq(1L), any(WatchlistEntry.class));
	}

	@Test
	void updateWatchlistEntryReturnsNotFoundWhenEntryMissing() throws Exception {
		when(watchlistEntryService.update(eq(10L), eq(1L), any(WatchlistEntry.class)))
			.thenThrow(new NoSuchElementException("Watchlist entry not found: 1"));

		mockMvc.perform(
			patch("/api/watchlists/10/entries/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WATCHING"
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist entry not found: 1"));
	}

	@Test
	void updateWatchlistEntryReturnsBadRequestWhenStatusInvalid() throws Exception {
		mockMvc.perform(
			patch("/api/watchlists/10/entries/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "INVALID_STATUS"
					}
					""")
		)
			.andExpect(status().isBadRequest());
	}

	@Test
	void deleteWatchlistEntryReturnsNoContent() throws Exception {
		doNothing().when(watchlistEntryService).deleteById(10L, 1L);

		mockMvc.perform(delete("/api/watchlists/10/entries/1").with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistEntryService).deleteById(10L, 1L);
	}

	@Test
	void deleteWatchlistEntryReturnsNoContentWhenEntryDoesNotExist() throws Exception {
		doNothing().when(watchlistEntryService).deleteById(10L, 99L);

		mockMvc.perform(delete("/api/watchlists/10/entries/99").with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistEntryService).deleteById(10L, 99L);
	}

	// ─── Viewer role restrictions ────────────────────────────────────────────

	@Test
	void updateWatchlistEntryReturnsForbiddenWhenViewer() throws Exception {
		doThrow(new ForbiddenException("Editor role required"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(
			patch("/api/watchlists/10/entries/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WATCHED"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Editor role required"));
	}

	@Test
	void deleteWatchlistEntryReturnsForbiddenWhenViewer() throws Exception {
		doThrow(new ForbiddenException("Editor role required"))
			.when(watchlistService).requireEditor(10L, 10L);

		mockMvc.perform(delete("/api/watchlists/10/entries/1").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Editor role required"));
	}

	// ─── Episode progress summary on entries ────────────────────────────────

	private static final Title TV_TITLE = new Title(
		30L, "1399", "TMDB", TitleType.TV, "Game of Thrones",
		null, null, null, Instant.EPOCH, Instant.EPOCH
	);

	@Test
	void getEntriesIncludesEpisodeSummaryForTvWithProgress() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry tvEntry = new WatchlistEntry(
			5L, 10L, 30L, WatchStatus.WATCHING, addedAt, addedAt, addedAt, null
		);

		when(watchlistEntryService.findByFilters(eq(10L), isNull(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(tvEntry)));
		when(titleService.findByIds(any())).thenReturn(Map.of(30L, TV_TITLE));
		when(episodeProgressRepository.summarizeByEntryIds(List.of(5L)))
			.thenReturn(List.<Object[]>of(new Object[] { 5L, 12L, 8L }));
		when(episodeProgressRepository.findLastWatchedByEntryIds(List.of(5L)))
			.thenReturn(List.<Object[]>of(new Object[] { 5L, 2, 5 }));

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(5))
			.andExpect(jsonPath("$.content[0].episodeProgress.watchedCount").value(8))
			.andExpect(jsonPath("$.content[0].episodeProgress.lastWatchedSeason").value(2))
			.andExpect(jsonPath("$.content[0].episodeProgress.lastWatchedEpisode").value(5));
	}

	@Test
	void getEntriesOmitsSummaryForTvWithNoProgress() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry tvEntry = new WatchlistEntry(
			5L, 10L, 30L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);

		when(watchlistEntryService.findByFilters(eq(10L), isNull(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(tvEntry)));
		when(titleService.findByIds(any())).thenReturn(Map.of(30L, TV_TITLE));
		when(episodeProgressRepository.summarizeByEntryIds(List.of(5L)))
			.thenReturn(List.of());
		when(episodeProgressRepository.findLastWatchedByEntryIds(List.of(5L)))
			.thenReturn(List.of());

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(5))
			.andExpect(jsonPath("$.content[0].episodeProgress").doesNotExist());
	}

	@Test
	void getEntriesOmitsSummaryForMovie() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry movieEntry = new WatchlistEntry(
			1L, 10L, 20L, WatchStatus.WANT_TO_WATCH, addedAt, addedAt, null, null
		);

		when(watchlistEntryService.findByFilters(eq(10L), isNull(), any(Pageable.class)))
			.thenReturn(new PageImpl<>(List.of(movieEntry)));

		mockMvc.perform(get("/api/watchlists/10/entries").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].episodeProgress").doesNotExist());
	}
}
