package com.wewatch.api.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GoogleTokenValidator {

	private final String clientId;
	private final RestClient restClient;

	public GoogleTokenValidator(
			@Value("${google.client-id}") String clientId,
			RestClient.Builder restClientBuilder) {
		this.clientId = clientId;
		this.restClient = restClientBuilder.build();
	}

	public GoogleIdentity validate(String credential) {
		Map<String, Object> payload;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> result = restClient.get()
				.uri("https://oauth2.googleapis.com/tokeninfo?id_token={token}", credential)
				.retrieve()
				.body(Map.class);
			payload = result;
		} catch (RestClientException e) {
			throw new InvalidCredentialException("Failed to validate Google token", e);
		}

		if (payload == null) {
			throw new InvalidCredentialException("Empty response from Google tokeninfo");
		}

		String aud = (String) payload.get("aud");
		if (!clientId.equals(aud)) {
			throw new InvalidCredentialException("Token audience mismatch");
		}

		return new GoogleIdentity(
			(String) payload.get("sub"),
			(String) payload.get("email"),
			(String) payload.get("name")
		);
	}

	public record GoogleIdentity(String sub, String email, String name) {}

	public static class InvalidCredentialException extends RuntimeException {
		public InvalidCredentialException(String message) {
			super(message);
		}

		public InvalidCredentialException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
