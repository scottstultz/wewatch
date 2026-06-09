package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.exception.DuplicateEmailException;
import com.wewatch.api.exception.InvalidCredentialsException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.AllowedEmailRepository;
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
	private AllowedEmailRepository allowedEmailRepository;

	@MockBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setUp() {
		when(allowedEmailRepository.existsByEmail(any())).thenReturn(true);
	}

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

	// ── Email sign-in tests ──────────────────────────────────

	@Test
	void exchangeTokenWithEmailProviderReturnsJwt() throws Exception {
		User user = new User(1L, "user@example.com", "Test User", Instant.now(), Instant.now(), "email", "user@example.com");
		when(userService.authenticateWithPassword("user@example.com", "password123")).thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("email-jwt-token");

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "email",
					"credential": "{\\"email\\":\\"user@example.com\\",\\"password\\":\\"password123\\"}"
				}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").value("email-jwt-token"));
	}

	@Test
	void exchangeTokenWithEmailProviderReturnsUnauthorizedForBadPassword() throws Exception {
		when(userService.authenticateWithPassword("user@example.com", "wrongpassword"))
			.thenThrow(new InvalidCredentialsException());

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "email",
					"credential": "{\\"email\\":\\"user@example.com\\",\\"password\\":\\"wrongpassword\\"}"
				}
				"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void exchangeTokenWithEmailProviderReturnsBadRequestForMalformedCredential() throws Exception {
		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "email",
					"credential": "not-valid-json"
				}
				"""))
			.andExpect(status().isBadRequest());
	}

	// ── Registration tests ───────────────────────────────────

	@Test
	void registerReturnsCreatedWithJwt() throws Exception {
		User user = new User(1L, "new@example.com", "New User", Instant.now(), Instant.now(), "email", "new@example.com");
		when(userService.registerWithPassword("new@example.com", "New User", "password123")).thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("new-user-jwt");

		mockMvc.perform(post("/api/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"email": "new@example.com",
					"displayName": "New User",
					"password": "password123"
				}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.token").value("new-user-jwt"));
	}

	@Test
	void registerReturnsConflictForDuplicateEmail() throws Exception {
		when(userService.registerWithPassword("existing@example.com", "User", "password123"))
			.thenThrow(new DuplicateEmailException("existing@example.com"));

		mockMvc.perform(post("/api/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"email": "existing@example.com",
					"displayName": "User",
					"password": "password123"
				}
				"""))
			.andExpect(status().isConflict());
	}

	@Test
	void registerReturnsBadRequestForMissingFields() throws Exception {
		mockMvc.perform(post("/api/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"email": "",
					"displayName": "",
					"password": ""
				}
				"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void registerReturnsBadRequestForShortPassword() throws Exception {
		mockMvc.perform(post("/api/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"email": "user@example.com",
					"displayName": "User",
					"password": "short"
				}
				"""))
			.andExpect(status().isBadRequest());
	}

	// ── Email allowlist tests ────────────────────────────────

	@Test
	void exchangeTokenReturnsForbiddenForNonAllowlistedGoogleEmail() throws Exception {
		GoogleIdentity identity = new GoogleIdentity("g-sub", "blocked@example.com", "Blocked");
		when(googleTokenValidator.validate("cred")).thenReturn(identity);
		when(allowedEmailRepository.existsByEmail("blocked@example.com")).thenReturn(false);

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "google",
					"credential": "cred"
				}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("This email is not authorized to use WeWatch."));
	}

	@Test
	void exchangeTokenReturnsForbiddenForNonAllowlistedEmailProvider() throws Exception {
		when(allowedEmailRepository.existsByEmail("blocked@example.com")).thenReturn(false);

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "email",
					"credential": "{\\"email\\":\\"blocked@example.com\\",\\"password\\":\\"password123\\"}"
				}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("This email is not authorized to use WeWatch."));
	}

	@Test
	void registerReturnsForbiddenForNonAllowlistedEmail() throws Exception {
		when(allowedEmailRepository.existsByEmail("blocked@example.com")).thenReturn(false);

		mockMvc.perform(post("/api/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"email": "blocked@example.com",
					"displayName": "Blocked User",
					"password": "password123"
				}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("This email is not authorized to use WeWatch."));
	}
}
