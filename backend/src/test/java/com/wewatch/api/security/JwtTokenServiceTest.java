package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.wewatch.api.model.User;

class JwtTokenServiceTest {

	private static final String SECRET = "test-secret-that-is-at-least-thirty-two-characters-long";

	private final JwtTokenService tokenService = new JwtTokenService(SECRET, 3600);

	@Test
	void generateTokenProducesDecodableJwt() {
		User user = new User(42L, "test@example.com", "Test User", Instant.now(), Instant.now(), "google", "g-123");

		String token = tokenService.generateToken(user);
		JwtDecoder decoder = tokenService.jwtDecoder();
		Jwt jwt = decoder.decode(token);

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("email")).isEqualTo("test@example.com");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("wewatch");
		assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
	}

	@Test
	void tamperedTokenIsRejected() {
		User user = new User(1L, "test@example.com", "Test", Instant.now(), Instant.now());

		String token = tokenService.generateToken(user);
		String tampered = token.substring(0, token.length() - 5) + "xxxxx";

		JwtDecoder decoder = tokenService.jwtDecoder();

		assertThatThrownBy(() -> decoder.decode(tampered))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void tokenFromDifferentSecretIsRejected() {
		User user = new User(1L, "test@example.com", "Test", Instant.now(), Instant.now());

		String token = tokenService.generateToken(user);

		JwtTokenService otherService = new JwtTokenService(
			"different-secret-that-is-also-at-least-thirty-two-chars", 3600);
		JwtDecoder otherDecoder = otherService.jwtDecoder();

		assertThatThrownBy(() -> otherDecoder.decode(token))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void expiredTokenIsRejected() {
		JwtTokenService shortLived = new JwtTokenService(SECRET, 0);
		User user = new User(1L, "test@example.com", "Test", Instant.now(), Instant.now());

		String token = shortLived.generateToken(user);
		JwtDecoder decoder = shortLived.jwtDecoder();

		assertThatThrownBy(() -> decoder.decode(token))
			.isInstanceOf(JwtException.class);
	}
}
