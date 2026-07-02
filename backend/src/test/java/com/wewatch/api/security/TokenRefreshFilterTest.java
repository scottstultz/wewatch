package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.ServletException;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.wewatch.api.model.User;

class TokenRefreshFilterTest {

	private static final String SECRET = "test-secret-that-is-at-least-thirty-two-characters-long";
	private static final long ONE_HOUR = 3600;
	private static final long REFRESH_WINDOW = 1800;

	private final JwtTokenService tokenService = new JwtTokenService(SECRET, ONE_HOUR);
	private final User user = new User(42L, "test@example.com", "Test User", Instant.now(), Instant.now(),
		"google", "g-123");

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void reissuesTokenWhenWithinRefreshWindow() throws ServletException, IOException {
		// A token with the full 1h lifetime remaining, checked against a window larger
		// than its lifetime, is always "near expiry"
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, ONE_HOUR + 60);
		authenticate();

		MockHttpServletResponse response = runFilter(filter, tokenService.generateToken(user));

		String refreshed = response.getHeader(TokenRefreshFilter.REFRESHED_TOKEN_HEADER);
		assertThat(refreshed).isNotNull();
		Jwt jwt = tokenService.jwtDecoder().decode(refreshed);
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
	}

	@Test
	void doesNotReissueTokenWithPlentyOfLifetimeLeft() throws ServletException, IOException {
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, REFRESH_WINDOW);
		authenticate();

		MockHttpServletResponse response = runFilter(filter, tokenService.generateToken(user));

		assertThat(response.getHeader(TokenRefreshFilter.REFRESHED_TOKEN_HEADER)).isNull();
	}

	@Test
	void doesNotReissueForUnauthenticatedRequest() throws ServletException, IOException {
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, ONE_HOUR + 60);

		MockHttpServletResponse response = runFilter(filter, tokenService.generateToken(user));

		assertThat(response.getHeader(TokenRefreshFilter.REFRESHED_TOKEN_HEADER)).isNull();
	}

	@Test
	void ignoresMissingAuthorizationHeader() throws ServletException, IOException {
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, ONE_HOUR + 60);
		authenticate();

		MockHttpServletResponse response = runFilter(filter, null);

		assertThat(response.getHeader(TokenRefreshFilter.REFRESHED_TOKEN_HEADER)).isNull();
	}

	@Test
	void ignoresMalformedBearerToken() throws ServletException, IOException {
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, ONE_HOUR + 60);
		authenticate();

		MockHttpServletResponse response = runFilter(filter, "not-a-jwt");

		assertThat(response.getHeader(TokenRefreshFilter.REFRESHED_TOKEN_HEADER)).isNull();
	}

	@Test
	void alwaysContinuesFilterChain() throws ServletException, IOException {
		TokenRefreshFilter filter = new TokenRefreshFilter(tokenService, ONE_HOUR + 60);
		authenticate();

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + tokenService.generateToken(user));
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isSameAs(request);
	}

	private void authenticate() {
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	private MockHttpServletResponse runFilter(TokenRefreshFilter filter, String bearerToken)
			throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (bearerToken != null) {
			request.addHeader("Authorization", "Bearer " + bearerToken);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}
}
