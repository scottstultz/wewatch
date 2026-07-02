package com.wewatch.api.security;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.wewatch.api.model.User;

/**
 * Sliding session refresh: when an authenticated request carries a token that is
 * within {@code jwt.refresh-window-seconds} of expiry, a freshly issued token is
 * returned in the {@value #REFRESHED_TOKEN_HEADER} response header. Clients store
 * the rotated token, so active sessions never hard-expire.
 */
@Component
public class TokenRefreshFilter extends OncePerRequestFilter {

	public static final String REFRESHED_TOKEN_HEADER = "X-Refreshed-Token";

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenService jwtTokenService;
	private final long refreshWindowSeconds;

	public TokenRefreshFilter(JwtTokenService jwtTokenService,
			@Value("${jwt.refresh-window-seconds:1800}") long refreshWindowSeconds) {
		this.jwtTokenService = jwtTokenService;
		this.refreshWindowSeconds = refreshWindowSeconds;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof User user) {
			Instant expiresAt = bearerTokenExpiry(request);
			if (expiresAt != null && expiresAt.isBefore(Instant.now().plusSeconds(refreshWindowSeconds))) {
				response.setHeader(REFRESHED_TOKEN_HEADER, jwtTokenService.generateToken(user));
			}
		}
		chain.doFilter(request, response);
	}

	/**
	 * Re-reads the expiry claim from the bearer token. The signature was already
	 * verified by the resource-server filter for the request to be authenticated,
	 * so no verification is repeated here.
	 */
	private Instant bearerTokenExpiry(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		try {
			Date expiration = SignedJWT.parse(header.substring(BEARER_PREFIX.length()))
				.getJWTClaimsSet().getExpirationTime();
			return expiration == null ? null : expiration.toInstant();
		} catch (ParseException e) {
			return null;
		}
	}
}
