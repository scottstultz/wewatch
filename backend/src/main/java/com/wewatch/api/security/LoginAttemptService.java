package com.wewatch.api.security;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import com.wewatch.api.exception.TooManyAttemptsException;

/**
 * In-memory failed-attempt throttle for the {@code /api/auth} endpoints (#318).
 *
 * <p>Two independent buckets — per-email and per-IP — each a fixed window that starts
 * on the first failure. Once a bucket reaches its limit within the window, {@link
 * #checkEmail}/{@link #checkIp} throw {@link TooManyAttemptsException} (HTTP 429) until
 * the window elapses. A successful sign-in resets the email bucket.
 *
 * <p>Backed by Caffeine so stale keys evict automatically ({@code expireAfterWrite}) and
 * memory stays bounded ({@code maximumSize}). This is single-instance state: on a scaled,
 * multi-node deployment each node would throttle independently — move to a shared store
 * (e.g. Redis) before horizontal scaling. The IP limit is deliberately higher than the
 * email limit so a shared household IP among allowlisted users is not falsely locked out.
 */
@Service
public class LoginAttemptService {

	/** Hard cap on tracked keys per bucket, bounding memory against distinct-key flooding. */
	private static final long MAX_KEYS = 100_000;

	private final int emailMaxAttempts;
	private final int ipMaxAttempts;
	private final long windowSeconds;
	private final Ticker ticker;
	private final Cache<String, Attempt> emailCache;
	private final Cache<String, Attempt> ipCache;

	@Autowired
	public LoginAttemptService(
		@Value("${app.auth.throttle.email-max-attempts:5}") int emailMaxAttempts,
		@Value("${app.auth.throttle.ip-max-attempts:20}") int ipMaxAttempts,
		@Value("${app.auth.throttle.window-seconds:900}") long windowSeconds
	) {
		this(emailMaxAttempts, ipMaxAttempts, windowSeconds, Ticker.systemTicker());
	}

	// Visible for testing: a fake Ticker drives window expiry without real sleeps.
	LoginAttemptService(int emailMaxAttempts, int ipMaxAttempts, long windowSeconds, Ticker ticker) {
		this.emailMaxAttempts = emailMaxAttempts;
		this.ipMaxAttempts = ipMaxAttempts;
		this.windowSeconds = windowSeconds;
		this.ticker = ticker;
		this.emailCache = newCache(windowSeconds, ticker);
		this.ipCache = newCache(windowSeconds, ticker);
	}

	private static Cache<String, Attempt> newCache(long windowSeconds, Ticker ticker) {
		return Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(windowSeconds))
			.maximumSize(MAX_KEYS)
			.ticker(ticker)
			.build();
	}

	/** Throws {@link TooManyAttemptsException} if this email has hit the failure limit. */
	public void checkEmail(String email) {
		check(emailCache, emailKey(email), emailMaxAttempts);
	}

	/** Throws {@link TooManyAttemptsException} if this IP has hit the failure limit. */
	public void checkIp(String ip) {
		check(ipCache, ip, ipMaxAttempts);
	}

	public void recordEmailFailure(String email) {
		record(emailCache, emailKey(email));
	}

	public void recordIpFailure(String ip) {
		record(ipCache, ip);
	}

	/** Clears the email bucket after a successful sign-in. */
	public void resetEmail(String email) {
		emailCache.invalidate(emailKey(email));
	}

	private void check(Cache<String, Attempt> cache, String key, int max) {
		Attempt attempt = cache.getIfPresent(key);
		if (attempt != null && attempt.count.get() >= max) {
			long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(ticker.read() - attempt.createdNanos);
			throw new TooManyAttemptsException(Math.max(1, windowSeconds - elapsedSeconds));
		}
	}

	private void record(Cache<String, Attempt> cache, String key) {
		// computeIfAbsent only writes on the first failure, so expireAfterWrite anchors the
		// window to that first attempt; incrementing the AtomicInteger is not a cache write.
		cache.asMap().computeIfAbsent(key, k -> new Attempt(ticker.read())).count.incrementAndGet();
	}

	private static String emailKey(String email) {
		return email == null ? "" : EmailNormalizer.normalize(email);
	}

	/** Clears every bucket. Operational/test reset — the throttle carries no persistent state. */
	public void clear() {
		emailCache.invalidateAll();
		ipCache.invalidateAll();
	}

	private static final class Attempt {
		private final long createdNanos;
		private final AtomicInteger count = new AtomicInteger();

		private Attempt(long createdNanos) {
			this.createdNanos = createdNanos;
		}
	}
}
