package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.exception.WatchlistMemberAlreadyExistsException;
import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.User;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistMemberId;
import com.wewatch.api.model.WatchlistType;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@WebMvcTest(WatchlistController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class WatchlistControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private WatchlistService watchlistService;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	private static final Instant EPOCH = Instant.EPOCH;

	private static final User TEST_USER = new User(10L, "caller@example.com", "Test Caller", EPOCH, EPOCH, "google", "sub-123");

	private static final Watchlist TEST_WATCHLIST = new Watchlist(1L, "My Watchlist", WatchlistType.SHARED, EPOCH, EPOCH);

	private static final WatchlistMember TEST_MEMBER = new WatchlistMember(
		new WatchlistMemberId(1L, 10L),
		MemberRole.OWNER,
		EPOCH,
		false
	);

	@BeforeEach
	void setUpMocks() {
		// Default: all member/owner guards pass — individual tests override to test 403/404
		when(watchlistService.requireMember(any(), any())).thenReturn(null);
		doNothing().when(watchlistService).requireOwner(any(), any());
		// batch and single member/user lookups drive toWatchlistResponse
		when(watchlistService.findMembersByWatchlistId(1L)).thenReturn(List.of(TEST_MEMBER));
		when(watchlistService.findMembersByWatchlistIds(anyList())).thenReturn(List.of(TEST_MEMBER));
		when(userService.findByIds(anyList())).thenReturn(Map.of(10L, TEST_USER));
		when(userService.findById(10L)).thenReturn(TEST_USER);
	}

	private static RequestPostProcessor asUser(User user) {
		return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	// ─── GET /api/watchlists ─────────────────────────────────────────────────

	@Test
	void getWatchlistsReturnsWatchlistsForUser() throws Exception {
		when(watchlistService.findByUserId(10L)).thenReturn(List.of(TEST_WATCHLIST));

		mockMvc.perform(get("/api/watchlists").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].name").value("My Watchlist"))
			.andExpect(jsonPath("$[0].type").value("SHARED"))
			.andExpect(jsonPath("$[0].members[0].userId").value(10))
			.andExpect(jsonPath("$[0].members[0].role").value("OWNER"));

		verify(watchlistService).findByUserId(10L);
	}

	@Test
	void getWatchlistsReturnsEmptyListWhenNoWatchlists() throws Exception {
		when(watchlistService.findByUserId(10L)).thenReturn(List.of());

		mockMvc.perform(get("/api/watchlists").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getWatchlistsReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/watchlists"))
			.andExpect(status().isUnauthorized());
	}

	// ─── POST /api/watchlists ────────────────────────────────────────────────

	@Test
	void createWatchlistReturnsCreatedWatchlist() throws Exception {
		when(watchlistService.createShared("Weekend Movies", 10L)).thenReturn(TEST_WATCHLIST);

		mockMvc.perform(
			post("/api/watchlists")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Weekend Movies"
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/watchlists/1"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.name").value("My Watchlist"))
			.andExpect(jsonPath("$.type").value("SHARED"))
			.andExpect(jsonPath("$.members[0].userId").value(10));

		verify(watchlistService).createShared("Weekend Movies", 10L);
	}

	@Test
	void createWatchlistReturnsBadRequestWhenNameBlank() throws Exception {
		mockMvc.perform(
			post("/api/watchlists")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": ""
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void createWatchlistReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(
			post("/api/watchlists")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Weekend Movies"
					}
					""")
		)
			.andExpect(status().isUnauthorized());
	}

	// ─── GET /api/watchlists/{watchlistId} ───────────────────────────────────

	@Test
	void getWatchlistReturnsWatchlist() throws Exception {
		when(watchlistService.findById(1L)).thenReturn(TEST_WATCHLIST);

		mockMvc.perform(get("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.name").value("My Watchlist"))
			.andExpect(jsonPath("$.members[0].userId").value(10));

		verify(watchlistService).findById(1L);
	}

	@Test
	void getWatchlistReturnsNotFoundWhenMissing() throws Exception {
		doThrow(new NoSuchElementException("Watchlist not found: 1"))
			.when(watchlistService).requireMember(1L, 10L);

		mockMvc.perform(get("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist not found: 1"));
	}

	@Test
	void getWatchlistReturnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).requireMember(1L, 10L);

		mockMvc.perform(get("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	// ─── PATCH /api/watchlists/{watchlistId} ─────────────────────────────────

	@Test
	void updateWatchlistReturnsUpdatedWatchlist() throws Exception {
		Watchlist updated = new Watchlist(1L, "Updated Name", WatchlistType.SHARED, EPOCH, Instant.parse("2026-06-01T12:00:00Z"));
		when(watchlistService.update(1L, "Updated Name")).thenReturn(updated);

		mockMvc.perform(
			patch("/api/watchlists/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Updated Name"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.name").value("Updated Name"))
			.andExpect(jsonPath("$.updatedAt").value("2026-06-01T12:00:00Z"));

		verify(watchlistService).update(1L, "Updated Name");
	}

	@Test
	void updateWatchlistReturnsForbiddenWhenNotOwner() throws Exception {
		doThrow(new ForbiddenException("Owner role required"))
			.when(watchlistService).requireOwner(1L, 10L);

		mockMvc.perform(
			patch("/api/watchlists/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "New Name"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Owner role required"));
	}

	@Test
	void updateWatchlistReturnsBadRequestWhenNameBlank() throws Exception {
		mockMvc.perform(
			patch("/api/watchlists/1")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": ""
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void getWatchlistsIncludesIsDefaultFlag() throws Exception {
		WatchlistMember defaultMember = new WatchlistMember(
			new WatchlistMemberId(1L, 10L),
			MemberRole.OWNER,
			EPOCH,
			true
		);
		when(watchlistService.findByUserId(10L)).thenReturn(List.of(TEST_WATCHLIST));
		when(watchlistService.findMembersByWatchlistIds(anyList())).thenReturn(List.of(defaultMember));

		mockMvc.perform(get("/api/watchlists").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].isDefault").value(true));
	}

	@Test
	void getWatchlistsIsDefaultFalseWhenNotDefault() throws Exception {
		when(watchlistService.findByUserId(10L)).thenReturn(List.of(TEST_WATCHLIST));

		mockMvc.perform(get("/api/watchlists").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].isDefault").value(false));
	}

	// ─── PATCH /api/watchlists/{watchlistId}/default ─────────────────────────

	@Test
	void setDefaultReturnsUpdatedWatchlist() throws Exception {
		doNothing().when(watchlistService).setDefault(1L, 10L);
		when(watchlistService.findById(1L)).thenReturn(TEST_WATCHLIST);
		WatchlistMember defaultMember = new WatchlistMember(
			new WatchlistMemberId(1L, 10L),
			MemberRole.OWNER,
			EPOCH,
			true
		);
		when(watchlistService.findMembersByWatchlistId(1L)).thenReturn(List.of(defaultMember));

		mockMvc.perform(patch("/api/watchlists/1/default").with(asUser(TEST_USER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.isDefault").value(true));

		verify(watchlistService).setDefault(1L, 10L);
	}

	@Test
	void setDefaultReturnsForbiddenWhenNotMember() throws Exception {
		doThrow(new ForbiddenException("Not a member of this watchlist"))
			.when(watchlistService).setDefault(1L, 10L);

		mockMvc.perform(patch("/api/watchlists/1/default").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Not a member of this watchlist"));
	}

	@Test
	void setDefaultReturnsNotFoundWhenWatchlistMissing() throws Exception {
		doThrow(new NoSuchElementException("Watchlist not found: 1"))
			.when(watchlistService).setDefault(1L, 10L);

		mockMvc.perform(patch("/api/watchlists/1/default").with(asUser(TEST_USER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Watchlist not found: 1"));
	}

	// ─── DELETE /api/watchlists/{watchlistId} ────────────────────────────────

	@Test
	void deleteWatchlistReturnsNoContent() throws Exception {
		doNothing().when(watchlistService).delete(1L, 10L);

		mockMvc.perform(delete("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistService).delete(1L, 10L);
	}

	@Test
	void deleteWatchlistReturnsForbiddenWhenNotOwner() throws Exception {
		doThrow(new ForbiddenException("Owner role required"))
			.when(watchlistService).delete(1L, 10L);

		mockMvc.perform(delete("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Owner role required"));
	}

	@Test
	void deleteWatchlistReturnsForbiddenForPersonalWatchlist() throws Exception {
		doThrow(new ForbiddenException("Personal watchlists cannot be deleted"))
			.when(watchlistService).delete(1L, 10L);

		mockMvc.perform(delete("/api/watchlists/1").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Personal watchlists cannot be deleted"));
	}

	// ─── POST /api/watchlists/{watchlistId}/members ──────────────────────────

	@Test
	void addMemberReturnsCreatedMember() throws Exception {
		User newUser = new User(20L, "new@example.com", "New Member", EPOCH, EPOCH, "google", "sub-456");
		WatchlistMember newMember = new WatchlistMember(
			new WatchlistMemberId(1L, 20L),
			MemberRole.EDITOR,
			EPOCH
		);
		when(userService.findByEmail("new@example.com")).thenReturn(newUser);
		when(watchlistService.addMember(1L, 20L, 10L)).thenReturn(newMember);
		when(userService.findById(20L)).thenReturn(newUser);

		mockMvc.perform(
			post("/api/watchlists/1/members")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "new@example.com"
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/watchlists/1/members/20"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.userId").value(20))
			.andExpect(jsonPath("$.email").value("new@example.com"))
			.andExpect(jsonPath("$.role").value("EDITOR"));

		verify(watchlistService).addMember(1L, 20L, 10L);
	}

	@Test
	void addMemberReturnsBadRequestWhenEmailInvalid() throws Exception {
		mockMvc.perform(
			post("/api/watchlists/1/members")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "not-an-email"
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void addMemberReturnsNotFoundWhenUserNotFound() throws Exception {
		when(userService.findByEmail("missing@example.com"))
			.thenThrow(new NoSuchElementException("User not found with email: missing@example.com"));

		mockMvc.perform(
			post("/api/watchlists/1/members")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "missing@example.com"
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("User not found with email: missing@example.com"));
	}

	@Test
	void addMemberReturnsConflictWhenAlreadyMember() throws Exception {
		when(userService.findByEmail("caller@example.com")).thenReturn(TEST_USER);
		when(watchlistService.addMember(1L, 10L, 10L))
			.thenThrow(new WatchlistMemberAlreadyExistsException(1L, 10L));

		mockMvc.perform(
			post("/api/watchlists/1/members")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "caller@example.com"
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("User 10 is already a member of watchlist 1"));
	}

	@Test
	void addMemberReturnsForbiddenWhenNotOwner() throws Exception {
		User newUser = new User(20L, "new@example.com", "New Member", EPOCH, EPOCH, "google", "sub-456");
		when(userService.findByEmail("new@example.com")).thenReturn(newUser);
		doThrow(new ForbiddenException("Owner role required"))
			.when(watchlistService).addMember(1L, 20L, 10L);

		mockMvc.perform(
			post("/api/watchlists/1/members")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "new@example.com"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Owner role required"));
	}

	// ─── PATCH /api/watchlists/{watchlistId}/members/{userId}/role ───────────

	@Test
	void updateMemberRoleReturnsUpdatedMember() throws Exception {
		User targetUser = new User(20L, "target@example.com", "Target User", EPOCH, EPOCH, "google", "sub-456");
		WatchlistMember updatedMember = new WatchlistMember(
			new WatchlistMemberId(1L, 20L),
			MemberRole.VIEWER,
			EPOCH,
			false
		);
		when(watchlistService.updateMemberRole(1L, 20L, MemberRole.VIEWER, 10L)).thenReturn(updatedMember);
		when(userService.findById(20L)).thenReturn(targetUser);

		mockMvc.perform(
			patch("/api/watchlists/1/members/20/role")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "role": "VIEWER"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(20))
			.andExpect(jsonPath("$.role").value("VIEWER"));

		verify(watchlistService).updateMemberRole(1L, 20L, MemberRole.VIEWER, 10L);
	}

	@Test
	void updateMemberRoleReturnsForbiddenWhenNotOwner() throws Exception {
		doThrow(new ForbiddenException("Owner role required"))
			.when(watchlistService).updateMemberRole(1L, 20L, MemberRole.VIEWER, 10L);

		mockMvc.perform(
			patch("/api/watchlists/1/members/20/role")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "role": "VIEWER"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Owner role required"));
	}

	@Test
	void updateMemberRoleReturnsForbiddenForSelfChange() throws Exception {
		doThrow(new ForbiddenException("Cannot change your own role"))
			.when(watchlistService).updateMemberRole(1L, 10L, MemberRole.VIEWER, 10L);

		mockMvc.perform(
			patch("/api/watchlists/1/members/10/role")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "role": "VIEWER"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Cannot change your own role"));
	}

	@Test
	void updateMemberRoleReturnsBadRequestForOwnerPromotion() throws Exception {
		doThrow(new IllegalArgumentException("Cannot promote to owner"))
			.when(watchlistService).updateMemberRole(1L, 20L, MemberRole.OWNER, 10L);

		mockMvc.perform(
			patch("/api/watchlists/1/members/20/role")
				.with(asUser(TEST_USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "role": "OWNER"
					}
					""")
		)
			.andExpect(status().isBadRequest());
	}

	// ─── DELETE /api/watchlists/{watchlistId}/members/{userId} ───────────────

	@Test
	void removeMemberReturnsNoContent() throws Exception {
		doNothing().when(watchlistService).removeMember(1L, 20L, 10L);

		mockMvc.perform(delete("/api/watchlists/1/members/20").with(asUser(TEST_USER)))
			.andExpect(status().isNoContent());

		verify(watchlistService).removeMember(1L, 20L, 10L);
	}

	@Test
	void removeMemberReturnsForbiddenWhenRemovingOwner() throws Exception {
		doThrow(new ForbiddenException("Cannot remove the owner of a watchlist"))
			.when(watchlistService).removeMember(1L, 10L, 10L);

		mockMvc.perform(delete("/api/watchlists/1/members/10").with(asUser(TEST_USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Cannot remove the owner of a watchlist"));
	}
}
