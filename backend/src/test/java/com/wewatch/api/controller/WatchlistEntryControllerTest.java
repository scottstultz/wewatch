package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import java.util.NoSuchElementException;

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

import com.wewatch.api.exception.DuplicateWatchlistEntryException;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistEntryService;

@WebMvcTest(WatchlistEntryController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class WatchlistEntryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private WatchlistEntryService watchlistEntryService;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	private static final User TEST_USER = new User(10L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");
	private static final User OTHER_USER = new User(99L, "other@example.com", "Other User", Instant.EPOCH, Instant.EPOCH, "google", "sub-999");

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	@Test
	void createWatchlistEntryReturnsCreatedEntry() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry createdEntry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			addedAt,
			addedAt,
			null,
			null
		);

		when(watchlistEntryService.create(any(WatchlistEntry.class))).thenReturn(createdEntry);

		mockMvc.perform(
			post("/api/users/10/watchlist")
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
			.andExpect(header().string("Location", "/api/users/10/watchlist/1"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.userId").value(10))
			.andExpect(jsonPath("$.titleId").value(20))
			.andExpect(jsonPath("$.status").value("WANT_TO_WATCH"))
			.andExpect(jsonPath("$.addedAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).create(any(WatchlistEntry.class));
	}

	@Test
	void createWatchlistEntryAllowsMissingStatus() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry createdEntry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			addedAt,
			addedAt,
			null,
			null
		);

		when(watchlistEntryService.create(any(WatchlistEntry.class))).thenReturn(createdEntry);

		mockMvc.perform(
			post("/api/users/10/watchlist")
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
			post("/api/users/10/watchlist")
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
	void createWatchlistEntryReturnsNotFoundWhenUserMissing() throws Exception {
		when(watchlistEntryService.create(any(WatchlistEntry.class)))
			.thenThrow(new NoSuchElementException("User not found: 10"));

		mockMvc.perform(
			post("/api/users/10/watchlist")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("User not found: 10"));
	}

	@Test
	void createWatchlistEntryReturnsNotFoundWhenTitleMissing() throws Exception {
		when(watchlistEntryService.create(any(WatchlistEntry.class)))
			.thenThrow(new NoSuchElementException("Title not found: 20"));

		mockMvc.perform(
			post("/api/users/10/watchlist")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Title not found: 20"));
	}

	@Test
	void createWatchlistEntryReturnsConflictWhenDuplicate() throws Exception {
		when(watchlistEntryService.create(any(WatchlistEntry.class)))
			.thenThrow(new DuplicateWatchlistEntryException(10L, 20L));

		mockMvc.perform(
			post("/api/users/10/watchlist")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message").value("Watchlist entry already exists for user 10 and title 20"));
	}

	@Test
	void createWatchlistEntryReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(
			post("/api/users/10/watchlist")
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
	void createWatchlistEntryReturnsForbiddenWhenUserIdDoesNotMatchToken() throws Exception {
		mockMvc.perform(
			post("/api/users/99/watchlist")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "titleId": 20
					}
					""")
		)
			.andExpect(status().isForbidden());
	}

	@Test
	void getWatchlistEntriesReturnsEntriesForUser() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			addedAt,
			addedAt,
			null,
			null
		);

		when(watchlistEntryService.findByFilters(10L, null)).thenReturn(List.of(entry));

		mockMvc.perform(get("/api/users/10/watchlist")
			.with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].userId").value(10))
			.andExpect(jsonPath("$[0].titleId").value(20))
			.andExpect(jsonPath("$[0].status").value("WANT_TO_WATCH"))
			.andExpect(jsonPath("$[0].addedAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$[0].updatedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).findByFilters(10L, null);
	}

	@Test
	void getWatchlistEntriesReturnsEmptyListWhenNoEntriesExist() throws Exception {
		when(watchlistEntryService.findByFilters(10L, null)).thenReturn(List.of());

		mockMvc.perform(get("/api/users/10/watchlist")
			.with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getWatchlistEntriesFiltersByStatus() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHING,
			addedAt,
			addedAt,
			addedAt,
			null
		);

		when(watchlistEntryService.findByFilters(10L, WatchStatus.WATCHING)).thenReturn(List.of(entry));

		mockMvc.perform(get("/api/users/10/watchlist")
			.with(asUser(TEST_USER))
			.param("status", "WATCHING"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].status").value("WATCHING"))
			.andExpect(jsonPath("$[0].startedAt").value("2026-04-28T12:00:00Z"));

		verify(watchlistEntryService).findByFilters(10L, WatchStatus.WATCHING);
	}

	@Test
	void getWatchlistEntriesReturnsNotFoundWhenUserMissing() throws Exception {
		when(watchlistEntryService.findByFilters(10L, null))
			.thenThrow(new NoSuchElementException("User not found: 10"));

		mockMvc.perform(get("/api/users/10/watchlist")
			.with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("User not found: 10"));
	}

	@Test
	void getWatchlistEntriesReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/users/10/watchlist"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void getWatchlistEntriesReturnsForbiddenWhenUserIdDoesNotMatchToken() throws Exception {
		mockMvc.perform(get("/api/users/99/watchlist")
			.with(asUser(TEST_USER)))
			.andExpect(status().isForbidden());
	}

	@Test
	void getWatchlistEntryReturnsEntryForUser() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		WatchlistEntry entry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WANT_TO_WATCH,
			addedAt,
			addedAt,
			null,
			null
		);

		when(watchlistEntryService.findById(10L, 1L)).thenReturn(entry);

		mockMvc.perform(get("/api/users/10/watchlist/1")
			.with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.userId").value(10))
			.andExpect(jsonPath("$.titleId").value(20))
			.andExpect(jsonPath("$.status").value("WANT_TO_WATCH"));

		verify(watchlistEntryService).findById(10L, 1L);
	}

	@Test
	void getWatchlistEntryReturnsNotFoundWhenEntryMissingForUser() throws Exception {
		when(watchlistEntryService.findById(10L, 1L))
			.thenThrow(new NoSuchElementException("Watchlist entry not found: 1"));

		mockMvc.perform(get("/api/users/10/watchlist/1")
			.with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Watchlist entry not found: 1"));
	}

	@Test
	void updateWatchlistEntryReturnsUpdatedEntry() throws Exception {
		Instant addedAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-28T13:00:00Z");
		WatchlistEntry updatedEntry = new WatchlistEntry(
			1L,
			10L,
			20L,
			WatchStatus.WATCHING,
			addedAt,
			updatedAt,
			updatedAt,
			null
		);

		when(watchlistEntryService.update(eq(10L), eq(1L), any(WatchlistEntry.class))).thenReturn(updatedEntry);

		mockMvc.perform(
			patch("/api/users/10/watchlist/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WATCHING"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.userId").value(10))
			.andExpect(jsonPath("$.status").value("WATCHING"))
			.andExpect(jsonPath("$.startedAt").value("2026-04-28T13:00:00Z"));

		verify(watchlistEntryService).update(eq(10L), eq(1L), any(WatchlistEntry.class));
	}

	@Test
	void updateWatchlistEntryReturnsNotFoundWhenEntryMissing() throws Exception {
		when(watchlistEntryService.update(eq(10L), eq(1L), any(WatchlistEntry.class)))
			.thenThrow(new NoSuchElementException("Watchlist entry not found: 1"));

		mockMvc.perform(
			patch("/api/users/10/watchlist/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "WATCHING"
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Watchlist entry not found: 1"));
	}

	@Test
	void updateWatchlistEntryReturnsBadRequestWhenStatusInvalid() throws Exception {
		mockMvc.perform(
			patch("/api/users/10/watchlist/1")
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

		mockMvc.perform(delete("/api/users/10/watchlist/1")
			.with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistEntryService).deleteById(10L, 1L);
	}

	@Test
	void deleteWatchlistEntryReturnsNoContentWhenEntryDoesNotExist() throws Exception {
		doNothing().when(watchlistEntryService).deleteById(10L, 99L);

		mockMvc.perform(delete("/api/users/10/watchlist/99")
			.with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistEntryService).deleteById(10L, 99L);
	}
}
