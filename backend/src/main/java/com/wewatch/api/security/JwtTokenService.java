package com.wewatch.api.security;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.wewatch.api.model.User;

@Component
public class JwtTokenService {

	private final SecretKey key;
	private final long expirationSeconds;

	public JwtTokenService(
			@Value("${jwt.secret}") String secret,
			@Value("${jwt.expiration-seconds:3600}") long expirationSeconds) {
		this.key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
		this.expirationSeconds = expirationSeconds;
	}

	public String generateToken(User user) {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.subject(user.getId().toString())
			.claim("email", user.getEmail())
			.issuer("wewatch")
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
		return NimbusJwtDecoder.withSecretKey(key)
			.macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
			.build();
	}
}
