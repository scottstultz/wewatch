package com.wewatch.api.exception;

public class DuplicateEmailException extends RuntimeException {

	public DuplicateEmailException(String email) {
		super("User email already exists: " + email);
	}
}
