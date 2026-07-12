package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import com.wewatch.api.exception.TooManyAttemptsException;

class LoginAttemptServiceTest {

	private final FakeTicker ticker = new FakeTicker();

	// email limit 3, ip limit 5, 900s window
	private LoginAttemptService service() {
		return new LoginAttemptService(3, 5, 900, ticker);
	}

	@Test
	void emailIsBlockedAfterReachingLimit() {
		LoginAttemptService service = service();

		assertThatCode(() -> service.checkEmail("user@example.com")).doesNotThrowAnyException();
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		// still under the limit of 3
		assertThatCode(() -> service.checkEmail("user@example.com")).doesNotThrowAnyException();

		service.recordEmailFailure("user@example.com");

		assertThatThrownBy(() -> service.checkEmail("user@example.com"))
			.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void emailKeyIsCaseAndWhitespaceInsensitive() {
		LoginAttemptService service = service();

		service.recordEmailFailure("User@Example.com");
		service.recordEmailFailure("  user@example.com  ");
		service.recordEmailFailure("USER@EXAMPLE.COM");

		assertThatThrownBy(() -> service.checkEmail("user@example.com"))
			.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void successResetsTheEmailCounter() {
		LoginAttemptService service = service();

		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");

		service.resetEmail("user@example.com");

		assertThatCode(() -> service.checkEmail("user@example.com")).doesNotThrowAnyException();
	}

	@Test
	void windowExpiryReallowsAttempts() {
		LoginAttemptService service = service();

		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		assertThatThrownBy(() -> service.checkEmail("user@example.com"))
			.isInstanceOf(TooManyAttemptsException.class);

		ticker.advanceSeconds(901);

		assertThatCode(() -> service.checkEmail("user@example.com")).doesNotThrowAnyException();
	}

	@Test
	void emailAndIpBucketsAreIndependent() {
		LoginAttemptService service = service();

		// Trip the email bucket (limit 3) but leave the IP bucket untouched.
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");

		assertThatThrownBy(() -> service.checkEmail("user@example.com"))
			.isInstanceOf(TooManyAttemptsException.class);
		assertThatCode(() -> service.checkIp("1.2.3.4")).doesNotThrowAnyException();
	}

	@Test
	void ipIsBlockedAfterReachingItsHigherLimit() {
		LoginAttemptService service = service();

		for (int i = 0; i < 5; i++) {
			service.recordIpFailure("1.2.3.4");
		}

		assertThatThrownBy(() -> service.checkIp("1.2.3.4"))
			.isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void retryAfterCountsDownWithinTheWindow() {
		LoginAttemptService service = service();

		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");
		service.recordEmailFailure("user@example.com");

		ticker.advanceSeconds(300);

		assertThatThrownBy(() -> service.checkEmail("user@example.com"))
			.isInstanceOfSatisfying(TooManyAttemptsException.class,
				e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(600));
	}

	private static final class FakeTicker implements Ticker {
		private long nanos;

		@Override
		public long read() {
			return nanos;
		}

		void advanceSeconds(long seconds) {
			nanos += TimeUnit.SECONDS.toNanos(seconds);
		}
	}
}
