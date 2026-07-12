package com.wewatch.api.exception;

/**
 * Raised when authentication attempts for an email or IP exceed the throttle
 * threshold (#318). Mapped to HTTP 429 with a {@code Retry-After} header by
 * {@link ApiExceptionHandler}.
 */
public class TooManyAttemptsException extends RuntimeException {

	private final long retryAfterSeconds;

	public TooManyAttemptsException(long retryAfterSeconds) {
		super("Too many attempts. Try again later.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
