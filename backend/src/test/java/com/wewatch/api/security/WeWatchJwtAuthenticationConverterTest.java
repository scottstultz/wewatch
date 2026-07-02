package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.wewatch.api.model.User;
import com.wewatch.api.service.UserService;

@ExtendWith(MockitoExtension.class)
class WeWatchJwtAuthenticationConverterTest {

	@Mock
	private UserService userService;

	@InjectMocks
	private WeWatchJwtAuthenticationConverter converter;

	@Test
	void convertExtractsUserIdFromSubClaim() {
		User user = new User(42L, "test@example.com", "Test User", Instant.now(), Instant.now());
		when(userService.findById(42L)).thenReturn(user);

		Jwt jwt = Jwt.withTokenValue("token")
			.header("alg", "HS256")
			.claim("sub", "42")
			.issuer("wewatch")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(3600))
			.build();

		AbstractAuthenticationToken result = converter.convert(jwt);

		assertThat(result).isInstanceOf(UsernamePasswordAuthenticationToken.class);
		assertThat(result.getPrincipal()).isEqualTo(user);
		assertThat(result.getAuthorities()).isEmpty();
	}

	@Test
	void convertThrowsInvalidBearerTokenWhenUserNoLongerExists() {
		when(userService.findById(42L)).thenThrow(new NoSuchElementException("User not found: 42"));

		Jwt jwt = jwtWithSubject("42");

		assertThatThrownBy(() -> converter.convert(jwt))
			.isInstanceOf(InvalidBearerTokenException.class)
			.hasCauseInstanceOf(NoSuchElementException.class);
	}

	@Test
	void convertThrowsInvalidBearerTokenForNonNumericSubject() {
		Jwt jwt = jwtWithSubject("not-a-number");

		assertThatThrownBy(() -> converter.convert(jwt))
			.isInstanceOf(InvalidBearerTokenException.class)
			.hasCauseInstanceOf(NumberFormatException.class);
	}

	private static Jwt jwtWithSubject(String subject) {
		return Jwt.withTokenValue("token")
			.header("alg", "HS256")
			.claim("sub", subject)
			.issuer("wewatch")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(3600))
			.build();
	}
}
