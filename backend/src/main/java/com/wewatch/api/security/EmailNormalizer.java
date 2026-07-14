package com.wewatch.api.security;

import java.util.Locale;

/**
 * Canonical form of an email address: trimmed and lowercased (#345).
 *
 * <p>Email is the identity key at every boundary — registration, sign-in, provider linking,
 * allowlist, watchlist member add — and before #345 each layer compared it differently, so
 * {@code Foo@x.com} and {@code foo@x.com} could become two accounts for one human. Every write
 * goes through here, and the {@code UNIQUE (lower(email))} index added in V25 is the backstop
 * if a future path forgets to.
 *
 * <p>{@link Locale#ROOT} is load-bearing: under a Turkish default locale {@code
 * "I".toLowerCase()} is the dotless {@code "ı"}, which would silently key an ASCII address to a
 * non-ASCII one.
 */
public final class EmailNormalizer {

	private EmailNormalizer() {
	}

	/** Returns the canonical form, or null for a null input. */
	public static String normalize(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}
}
