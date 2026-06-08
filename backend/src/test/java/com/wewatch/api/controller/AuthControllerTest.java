package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.model.User;
import com.wewatch.api.security.GoogleTokenValidator;
import com.wewatch.api.security.GoogleTokenValidator.GoogleIdentity;
import com.wewatch.api.security.GoogleTokenValidator.InvalidCredentialException;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private GoogleTokenValidator googleTokenValidator;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtTokenService jwtTokenService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@Test
	void exchangeTokenReturnsWeWatchJwt() throws Exception {
		GoogleIdentity identity = new GoogleIdentity("g-sub-123", "user@example.com", "Test User");
		User user = new User(1L, "user@example.com", "Test User", Instant.now(), Instant.now(), "google", "g-sub-123");

		when(googleTokenValidator.validate("valid-google-credential")).thenReturn(identity);
		when(userService.findOrCreateByProviderIdentity("google", "g-sub-123", "user@example.com", "Test User"))
			.thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("wewatch-jwt-token");

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "google",
					"credential": "valid-google-credential"
				}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").value("wewatch-jwt-token"));
	}

	@Test
	void exchangeTokenReturnsUnauthorizedForInvalidCredential() throws Exception {
		when(googleTokenValidator.validate(any()))
			.thenThrow(new InvalidCredentialException("Invalid token"));

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "google",
					"credential": "invalid-credential"
				}
				"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void exchangeTokenReturnsBadRequestForUnsupportedProvider() throws Exception {
		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "apple",
					"credential": "some-credential"
				}
				"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void exchangeTokenReturnsBadRequestForMissingFields() throws Exception {
		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": ""
				}
				"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void exchangeTokenIsAccessibleWithoutAuthentication() throws Exception {
		GoogleIdentity identity = new GoogleIdentity("g-sub", "user@example.com", "User");
		User user = new User(1L, "user@example.com", "User", Instant.now(), Instant.now(), "google", "g-sub");

		when(googleTokenValidator.validate("cred")).thenReturn(identity);
		when(userService.findOrCreateByProviderIdentity("google", "g-sub", "user@example.com", "User"))
			.thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("token");

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "google",
					"credential": "cred"
				}
				"""))
			.andExpect(status().isOk());
	}
}
