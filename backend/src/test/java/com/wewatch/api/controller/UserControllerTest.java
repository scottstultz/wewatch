package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UserService userService;

	@MockBean
	private AllowedEmailRepository allowedEmailRepository;

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
	void getMeReturnsAuthenticatedUser() throws Exception {
		mockMvc.perform(get("/api/users/me")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.email").value("test@example.com"))
			.andExpect(jsonPath("$.displayName").value("Test User"));
	}

	@Test
	void getMeReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void getUserReturnsPersistedUser() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		com.wewatch.api.model.User existingUser = new com.wewatch.api.model.User(
			1L,
			"user@example.com",
			"Scott",
			createdAt,
			createdAt
		);

		when(userService.findById(1L)).thenReturn(existingUser);

		mockMvc.perform(get("/api/users/1")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.email").value("user@example.com"))
			.andExpect(jsonPath("$.displayName").value("Scott"));
	}

	@Test
	void updateUserReturnsUpdatedUser() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-29T12:00:00Z");
		User updatedUser = new User(1L, "updated@example.com", "Scott Stultz", createdAt, updatedAt);

		when(allowedEmailRepository.existsByEmailIgnoreCase("updated@example.com")).thenReturn(true);
		when(userService.update(1L, "updated@example.com", "Scott Stultz")).thenReturn(updatedUser);

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "updated@example.com",
					  "displayName": "Scott Stultz"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.email").value("updated@example.com"))
			.andExpect(jsonPath("$.displayName").value("Scott Stultz"))
			.andExpect(jsonPath("$.createdAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-29T12:00:00Z"));

		verify(userService).update(1L, "updated@example.com", "Scott Stultz");
	}

	@Test
	void updateUserSupportsPartialPayload() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-29T12:00:00Z");
		User updatedUser = new User(1L, "user@example.com", "Scott Stultz", createdAt, updatedAt);

		when(userService.update(1L, null, "Scott Stultz")).thenReturn(updatedUser);

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayName": "Scott Stultz"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.email").value("user@example.com"))
			.andExpect(jsonPath("$.displayName").value("Scott Stultz"));

		verify(userService).update(1L, null, "Scott Stultz");
	}

	@Test
	void updateUserReturnsForbiddenForOtherUser() throws Exception {
		mockMvc.perform(
			patch("/api/users/42")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayName": "Hacked"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.message").value("Cannot update another user's profile"));
	}

	@Test
	void updateUserReturnsNotFoundWhenMissing() throws Exception {
		when(userService.update(1L, null, "Scott")).thenThrow(new NoSuchElementException("User not found: 1"));

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayName": "Scott"
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("User not found: 1"));
	}

	@Test
	void updateUserReturnsBadRequestForInvalidPayload() throws Exception {
		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "not-an-email"
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void updateUserReturnsConflictForDuplicateEmail() throws Exception {
		when(allowedEmailRepository.existsByEmailIgnoreCase("other@example.com")).thenReturn(true);
		when(userService.update(1L, "other@example.com", null))
			.thenThrow(new DuplicateEmailException("other@example.com"));

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "other@example.com"
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message").value("User email already exists: other@example.com"));
	}

	@Test
	void getUsersListingIsGone() throws Exception {
		mockMvc.perform(get("/api/users")
			.header("Authorization", "Bearer test-token")
			.param("email", "user@example.com"))
			.andExpect(status().isNotFound());
	}

	@Test
	void getUserReturnsForbiddenForOtherUser() throws Exception {
		mockMvc.perform(get("/api/users/42")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.message").value("Cannot view another user's profile"));
	}

	@Test
	void updateUserReturnsForbiddenWhenNewEmailNotAllowlisted() throws Exception {
		when(allowedEmailRepository.existsByEmailIgnoreCase("intruder@example.com")).thenReturn(false);

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "intruder@example.com"
					}
					""")
		)
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.message").value("This email is not authorized to use WeWatch."));

		verify(userService, never()).update(any(), any(), any());
	}

	@Test
	void updateUserSkipsAllowlistCheckWhenEmailUnchanged() throws Exception {
		User updatedUser = new User(1L, "test@example.com", "New Name", Instant.EPOCH, Instant.EPOCH);
		when(userService.update(1L, "Test@Example.com", "New Name")).thenReturn(updatedUser);

		mockMvc.perform(
			patch("/api/users/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "Test@Example.com",
					  "displayName": "New Name"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("New Name"));

		verify(allowedEmailRepository, never()).existsByEmailIgnoreCase(any());
	}

	@Test
	void getUserReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/users/1"))
			.andExpect(status().isUnauthorized());
	}
}
