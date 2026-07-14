package com.wewatch.api.exception;

/**
 * A first-time provider sign-in matched an existing account by email, but that account already
 * holds credentials (a password hash, or a different provider identity). Linking would hand the
 * caller an account someone else can still sign into — see #342.
 */
public class AccountLinkConflictException extends RuntimeException {

	public AccountLinkConflictException() {
		super("An account with this email already exists. Sign in with your password instead.");
	}
}
