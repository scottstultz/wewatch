package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.wewatch.api.security.ClientIpResolver;
import com.wewatch.api.security.GoogleTokenValidator;
import com.wewatch.api.security.GoogleTokenValidator.GoogleIdentity;
import com.wewatch.api.security.GoogleTokenValidator.InvalidCredentialException;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.LoginAttemptService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;

@WebMvcTest(value = AuthController.class, properties = {
	"app.auth.throttle.email-max-attempts=3",
	"app.auth.throttle.ip-max-attempts=4",
	// One specific proxy, so the 10.0.0.x addresses the other tests use as distinct
	// clients stay untrusted and keep keying on their own peer address.
	"app.auth.throttle.trusted-proxies=10.9.9.9"
})
@Import({ SecurityConfig.class, LoginAttemptService.class, ClientIpResolver.class })
@ActiveProfiles("local")
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoginAttemptService loginAttemptService;

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
		when(allowedEmailRepository.existsByEmailIgnoreCase(any())).thenReturn(true);
		// The throttle bean is a shared singleton across test methods — reset it so
		// failures recorded by one test don't spill into the next.
		loginAttemptService.clear();
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

	/**
	 * The sign-in credential is parsed out of a JSON *string*, so it never passes through @Email —
	 * it is the one email entry point that can carry surrounding whitespace. The allowlist gate runs
	 * first and existsByEmailIgnoreCase folds case but not whitespace, so before #345 an untrimmed
	 * address was answered 403 "not allowed" instead of matching its own allowlist row.
	 */
	@Test
	void emailSignInCanonicalizesTheCredentialBeforeTheAllowlistGate() throws Exception {
		// Only the canonical address is allowlisted — the blanket any() stub in setUp() would let
		// any string through and hide the bug this pins.
		reset(allowedEmailRepository);
		when(allowedEmailRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

		User user = new User(1L, "user@example.com", "Test User", Instant.now(), Instant.now(), "email", "user@example.com");
		when(userService.authenticateWithPassword("user@example.com", "password123")).thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("email-jwt-token");

		mockMvc.perform(post("/api/auth/token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
					"provider": "email",
					"credential": "{\\"email\\":\\"  User@Example.COM  \\",\\"password\\":\\"password123\\"}"
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
		when(allowedEmailRepository.existsByEmailIgnoreCase("blocked@example.com")).thenReturn(false);

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
		when(allowedEmailRepository.existsByEmailIgnoreCase("blocked@example.com")).thenReturn(false);

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
		when(allowedEmailRepository.existsByEmailIgnoreCase("blocked@example.com")).thenReturn(false);

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

	@Test
	void exchangeTokenAllowsGoogleEmailRegardlessOfCase() throws Exception {
		GoogleIdentity identity = new GoogleIdentity("g-sub", "User@Example.com", "Test User");
		User user = new User(1L, "User@Example.com", "Test User", Instant.now(), Instant.now(), "google", "g-sub");
		when(googleTokenValidator.validate("cred")).thenReturn(identity);
		when(allowedEmailRepository.existsByEmailIgnoreCase("User@Example.com")).thenReturn(true);
		when(userService.findOrCreateByProviderIdentity("google", "g-sub", "User@Example.com", "Test User")).thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("wewatch-jwt-token");

		mockMvc.perform(post("/api/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
						"provider": "google",
						"credential": "cred"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").value("wewatch-jwt-token"));
	}

	// ── Throttling tests (#318) ──────────────────────────────

	@Test
	void repeatedFailedEmailSignInsAreThrottledWith429() throws Exception {
		when(userService.authenticateWithPassword("user@example.com", "wrongpass"))
			.thenThrow(new InvalidCredentialsException());

		// email-max-attempts=3: the first three are rejected as bad credentials...
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(emailSignIn("user@example.com", "wrongpass"))
				.andExpect(status().isUnauthorized());
		}

		// ...the fourth is throttled before the password is even checked.
		mockMvc.perform(emailSignIn("user@example.com", "wrongpass"))
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void failedSignInsShareOneThrottleBucketAcrossEmailCasings() throws Exception {
		when(userService.authenticateWithPassword(any(), any()))
			.thenThrow(new InvalidCredentialsException());

		// Varying the casing must not mint a fresh 3-attempt budget per variant — the throttle
		// keys on the canonical address, same as every other boundary after #345.
		mockMvc.perform(emailSignIn("user@example.com", "wrongpass")).andExpect(status().isUnauthorized());
		mockMvc.perform(emailSignIn("User@Example.com", "wrongpass")).andExpect(status().isUnauthorized());
		mockMvc.perform(emailSignIn("USER@EXAMPLE.COM", "wrongpass")).andExpect(status().isUnauthorized());

		mockMvc.perform(emailSignIn("uSeR@eXaMpLe.CoM", "wrongpass"))
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void throttledResponseHasStandardErrorShapeAndRetryAfterHeader() throws Exception {
		when(userService.authenticateWithPassword("user@example.com", "wrongpass"))
			.thenThrow(new InvalidCredentialsException());

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(emailSignIn("user@example.com", "wrongpass"));
		}

		mockMvc.perform(emailSignIn("user@example.com", "wrongpass"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(jsonPath("$.error").value("Too Many Requests"))
			.andExpect(jsonPath("$.message").value("Too many attempts. Try again later."))
			.andExpect(jsonPath("$.path").value("/api/auth/token"));
	}

	@Test
	void rapidAttemptsFromOneIpAcrossEmailsAreThrottled() throws Exception {
		when(userService.authenticateWithPassword(any(), any()))
			.thenThrow(new InvalidCredentialsException());

		// ip-max-attempts=4: four failures from the same IP, each a different email
		// (so no single email bucket trips), exhaust the IP bucket.
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(emailSignIn("user" + i + "@example.com", "wrongpass"))
				.andExpect(status().isUnauthorized());
		}

		// A fresh email from the same IP is now throttled on the IP bucket alone.
		mockMvc.perform(emailSignIn("someone-else@example.com", "wrongpass"))
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulSignInResetsTheEmailCounter() throws Exception {
		when(userService.authenticateWithPassword("user@example.com", "wrongpass"))
			.thenThrow(new InvalidCredentialsException());
		User user = new User(1L, "user@example.com", "User", Instant.now(), Instant.now(), "email", "user@example.com");
		when(userService.authenticateWithPassword("user@example.com", "goodpass")).thenReturn(user);
		when(jwtTokenService.generateToken(user)).thenReturn("token");

		// Each request uses a distinct IP so only the email bucket is exercised here.
		int ip = 0;
		mockMvc.perform(emailSignIn("user@example.com", "wrongpass").with(remoteAddr(ip++)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(emailSignIn("user@example.com", "wrongpass").with(remoteAddr(ip++)))
			.andExpect(status().isUnauthorized());

		// A success clears the email counter.
		mockMvc.perform(emailSignIn("user@example.com", "goodpass").with(remoteAddr(ip++)))
			.andExpect(status().isOk());

		// The counter restarted from zero: three more failures are all 401, none 429 —
		// which could only happen if the earlier two failures were forgotten.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(emailSignIn("user@example.com", "wrongpass").with(remoteAddr(ip++)))
				.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void throttlingOneClientBehindTheProxyDoesNotLockOutAnother() throws Exception {
		when(userService.authenticateWithPassword(any(), any()))
			.thenThrow(new InvalidCredentialsException());

		// Every request arrives from the nginx container's address; only X-Forwarded-For
		// distinguishes the two clients. Before #336 the peer was the bucket key, so the
		// attacker's four failures locked out the whole world.
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(emailSignIn("attacker" + i + "@example.com", "wrongpass").with(proxiedFrom("203.0.113.7")))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(emailSignIn("attacker@example.com", "wrongpass").with(proxiedFrom("203.0.113.7")))
			.andExpect(status().isTooManyRequests());

		// A different client through the same proxy still gets to fail on its own merits.
		mockMvc.perform(emailSignIn("bystander@example.com", "wrongpass").with(proxiedFrom("198.51.100.9")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aSpoofedForwardedForFromAnUntrustedPeerCannotDodgeTheIpBucket() throws Exception {
		when(userService.authenticateWithPassword(any(), any()))
			.thenThrow(new InvalidCredentialsException());

		// 203.0.113.7 is not a trusted proxy, so its header is ignored and all five requests
		// key on the peer — rotating the claimed client IP buys nothing.
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(emailSignIn("user" + i + "@example.com", "wrongpass")
					.with(spoofedFrom("203.0.113.7", "198.51.100." + i)))
				.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(emailSignIn("user5@example.com", "wrongpass")
				.with(spoofedFrom("203.0.113.7", "198.51.100.99")))
			.andExpect(status().isTooManyRequests());
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder emailSignIn(
			String email, String password) {
		String credential = "{\\\"email\\\":\\\"" + email + "\\\",\\\"password\\\":\\\"" + password + "\\\"}";
		String body = "{\"provider\":\"email\",\"credential\":\"" + credential + "\"}";
		return post("/api/auth/token").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(int n) {
		return request -> {
			request.setRemoteAddr("10.0.0." + n);
			return request;
		};
	}

	/** A request as nginx delivers it: proxy peer, real client in X-Forwarded-For. */
	private static org.springframework.test.web.servlet.request.RequestPostProcessor proxiedFrom(String clientIp) {
		return request -> {
			request.setRemoteAddr("10.9.9.9");
			request.addHeader("X-Forwarded-For", clientIp);
			return request;
		};
	}

	/** A direct caller inventing a client IP it isn't entitled to claim. */
	private static org.springframework.test.web.servlet.request.RequestPostProcessor spoofedFrom(
			String peer, String claimedIp) {
		return request -> {
			request.setRemoteAddr(peer);
			request.addHeader("X-Forwarded-For", claimedIp);
			return request;
		};
	}
}
