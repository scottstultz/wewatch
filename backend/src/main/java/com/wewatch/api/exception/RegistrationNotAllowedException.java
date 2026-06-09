package com.wewatch.api.exception;

public class RegistrationNotAllowedException extends RuntimeException {

	public RegistrationNotAllowedException() {
		super("This email is not authorized to use WeWatch.");
	}
}
