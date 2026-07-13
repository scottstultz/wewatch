package com.wewatch.api.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.wewatch.api.model.User;

@Component
public class JwtTokenService {

	static final String ISSUER = "wewatch";

	/** HS256 requires a 256-bit key; a shorter secret fails inside MACSigner at first sign-in, not at startup. */
	private static final int MIN_SECRET_BYTES = 32;

	private final SecretKey key;
	private final long expirationSeconds;

	public JwtTokenService(
			@Value("${jwt.secret}") String secret,
			@Value("${jwt.expiration-seconds:3600}") long expirationSeconds) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException(
				"JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (256 bits) for HS256, but was "
					+ keyBytes.length + ". Generate one with: openssl rand -hex 32");
		}
		this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
		this.expirationSeconds = expirationSeconds;
	}

	public String generateToken(User user) {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.subject(user.getId().toString())
			.claim("email", user.getEmail())
			.issuer(ISSUER)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(expirationSeconds)))
			.build();

		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(new MACSigner(key));
		} catch (JOSEException e) {
			throw new RuntimeException("Failed to sign JWT", e);
		}
		return jwt.serialize();
	}

	public JwtDecoder jwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}
}
