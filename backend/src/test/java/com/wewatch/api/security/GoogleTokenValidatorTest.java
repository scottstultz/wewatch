package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.wewatch.api.security.GoogleTokenValidator.GoogleIdentity;
import com.wewatch.api.security.GoogleTokenValidator.InvalidCredentialException;

class GoogleTokenValidatorTest {

	private static final String CLIENT_ID = "test-client-id";

	private MockRestServiceServer mockServer;
	private GoogleTokenValidator validator;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		mockServer = MockRestServiceServer.bindTo(builder).build();
		validator = new GoogleTokenValidator(CLIENT_ID, builder);
	}

	@Test
	void validateReturnsIdentityForValidToken() {
		mockServer.expect(requestTo("https://oauth2.googleapis.com/tokeninfo?id_token=valid-token"))
			.andRespond(withSuccess("""
				{
					"sub": "google-sub-123",
					"email": "user@example.com",
					"name": "Test User",
					"aud": "test-client-id",
					"email_verified": "true"
				}
				""", MediaType.APPLICATION_JSON));

		GoogleIdentity identity = validator.validate("valid-token");

		assertThat(identity.sub()).isEqualTo("google-sub-123");
		assertThat(identity.email()).isEqualTo("user@example.com");
		assertThat(identity.name()).isEqualTo("Test User");
		mockServer.verify();
	}

	@Test
	void validateThrowsWhenAudienceMismatch() {
		mockServer.expect(requestTo("https://oauth2.googleapis.com/tokeninfo?id_token=bad-aud-token"))
			.andRespond(withSuccess("""
				{
					"sub": "google-sub-123",
					"email": "user@example.com",
					"name": "Test User",
					"aud": "wrong-client-id"
				}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> validator.validate("bad-aud-token"))
			.isInstanceOf(InvalidCredentialException.class)
			.hasMessageContaining("audience mismatch");
		mockServer.verify();
	}

	@Test
	void validateThrowsWhenGoogleReturnsError() {
		mockServer.expect(requestTo("https://oauth2.googleapis.com/tokeninfo?id_token=invalid-token"))
			.andRespond(withServerError());

		assertThatThrownBy(() -> validator.validate("invalid-token"))
			.isInstanceOf(InvalidCredentialException.class);
		mockServer.verify();
	}
}
