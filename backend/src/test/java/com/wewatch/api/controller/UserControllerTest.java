package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.service.UserService;

@WebMvcTest(UserController.class)
@ActiveProfiles("local")
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UserService userService;

	@Test
	void createUserReturnsCreatedUser() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		com.wewatch.api.model.User createdUser = new com.wewatch.api.model.User(
			1L,
			"user@example.com",
			"Scott",
			createdAt,
			createdAt
		);

		when(userService.create(any(com.wewatch.api.model.User.class))).thenReturn(createdUser);

		mockMvc.perform(
			post("/api/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "user@example.com",
					  "displayName": "Scott"
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/users/1"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.email").value("user@example.com"))
			.andExpect(jsonPath("$.displayName").value("Scott"))
			.andExpect(jsonPath("$.createdAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-28T12:00:00Z"));

		verify(userService).create(any(com.wewatch.api.model.User.class));
	}

	@Test
	void createUserReturnsBadRequestForInvalidPayload() throws Exception {
		mockMvc.perform(
			post("/api/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "",
					  "displayName": ""
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void createUserReturnsConflictForDuplicateEmail() throws Exception {
		when(userService.create(any(com.wewatch.api.model.User.class)))
			.thenThrow(new DuplicateEmailException("user@example.com"));

		mockMvc.perform(
			post("/api/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "user@example.com",
					  "displayName": "Scott"
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message").value("User email already exists: user@example.com"));
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

		mockMvc.perform(get("/api/users/1"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.email").value("user@example.com"))
			.andExpect(jsonPath("$.displayName").value("Scott"));
	}

	@Test
	void getUserReturnsNotFoundWhenMissing() throws Exception {
		when(userService.findById(42L)).thenThrow(new NoSuchElementException("User not found: 42"));

		mockMvc.perform(get("/api/users/42"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("User not found: 42"));
	}
}
