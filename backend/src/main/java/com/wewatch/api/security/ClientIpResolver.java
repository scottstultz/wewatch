package com.wewatch.api.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for the per-IP auth throttle (#336).
 *
 * <p>{@code getRemoteAddr()} is the peer that opened the socket. Behind the nginx frontend
 * — which proxies {@code /api/} to this service — that peer is nginx for <em>every</em> user,
 * so keying the throttle on it puts the whole world in one bucket: 20 bad passwords from
 * anywhere locks out every sign-in for the rest of the window (#318's per-IP bucket, defeated).
 *
 * <p>The forwarding headers can't simply be trusted, though: a caller reaching this service
 * directly could set {@code X-Forwarded-For} to a fresh value per request and never fill a
 * bucket at all. So the peer is checked first:
 *
 * <ol>
 *   <li>If {@code getRemoteAddr()} is <b>not</b> a configured trusted proxy, return it and
 *       ignore the headers entirely — a direct caller cannot choose its own bucket key.</li>
 *   <li>Otherwise walk {@code X-Forwarded-For} <b>right to left</b>, skipping hops that are
 *       themselves trusted proxies, and return the first untrusted one. Direction matters:
 *       nginx's {@code $proxy_add_x_forwarded_for} <em>appends</em> the peer to whatever the
 *       client sent, so a client that injects {@code X-Forwarded-For: 1.2.3.4} produces
 *       {@code "1.2.3.4, <real client>"} — the rightmost untrusted entry is the real one, and
 *       everything to its left is attacker-controlled noise.</li>
 *   <li>Fall back to {@code X-Real-IP} (nginx sets it to {@code $remote_addr}, overwriting any
 *       client-supplied value), then to the peer.</li>
 * </ol>
 *
 * <p>Trusted proxies come from {@code app.auth.throttle.trusted-proxies}: a comma-separated
 * list of literal addresses or CIDR blocks, IPv4 or IPv6. The default is loopback plus the
 * RFC 1918 ranges (the same set as Tomcat's {@code internal-proxies}), which covers the
 * docker-compose topology where nginx reaches the backend from the bridge network. An empty
 * value trusts nothing, reverting to peer-only behavior.
 */
@Component
public class ClientIpResolver {

	private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

	/** Bounds the work a hostile header can cause; real chains are 1–3 hops. */
	private static final int MAX_FORWARDED_HOPS = 20;

	private final List<CidrBlock> trustedProxies;

	public ClientIpResolver(
		@Value("${app.auth.throttle.trusted-proxies:}") String trustedProxies
	) {
		this.trustedProxies = parseBlocks(trustedProxies);
		// #408: a misconfigured or misspelled APP_AUTH_THROTTLE_TRUSTED_PROXIES env var on
		// Railway (which REPLACES rather than extends the property) fails silently otherwise —
		// the resolver just falls back to peer-only behavior with no error anywhere. This count
		// is enough to sanity-check the env var actually took effect without logging the CIDR
		// list itself, which isn't sensitive but also isn't this log's job.
		log.info("ClientIpResolver configured with {} trusted-proxy block(s)", this.trustedProxies.size());
	}

	/** The originating client IP, or the peer address when no proxy is trusted. */
	public String resolve(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		if (!isTrustedProxy(peer)) {
			return peer;
		}

		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null) {
			String[] hops = forwardedFor.split(",");
			int examined = 0;
			for (int i = hops.length - 1; i >= 0 && examined < MAX_FORWARDED_HOPS; i--, examined++) {
				String hop = normalize(hops[i]);
				if (hop == null || isTrustedProxy(hop)) {
					continue;
				}
				return hop;
			}
		}

		String realIp = normalize(request.getHeader("X-Real-IP"));
		if (realIp != null) {
			return realIp;
		}
		return peer;
	}

	private boolean isTrustedProxy(String ip) {
		InetAddress address = parseLiteral(ip);
		if (address == null) {
			return false;
		}
		return trustedProxies.stream().anyMatch(block -> block.contains(address));
	}

	/** Trims an X-Forwarded-For entry and strips any port suffix; null if it isn't a literal IP. */
	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String hop = value.trim();
		if (hop.startsWith("[")) {
			// "[2001:db8::1]:443" — bracketed IPv6, optionally with a port.
			int close = hop.indexOf(']');
			hop = close > 0 ? hop.substring(1, close) : hop.substring(1);
		} else {
			int colon = hop.indexOf(':');
			// A single colon is "1.2.3.4:5678"; several mean a bare IPv6 address, which
			// has no port to strip.
			if (colon > 0 && hop.indexOf(':', colon + 1) < 0) {
				hop = hop.substring(0, colon);
			}
		}
		return parseLiteral(hop) == null ? null : hop;
	}

	/**
	 * Parses a literal IPv4/IPv6 address, or null. Anything that isn't literal is rejected
	 * before {@link InetAddress#getByName} sees it — otherwise a header carrying a hostname
	 * would send this service off to do a DNS lookup on every auth request.
	 */
	private static InetAddress parseLiteral(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		boolean literal = value.indexOf(':') >= 0
			|| value.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
		if (!literal) {
			return null;
		}
		try {
			return InetAddress.getByName(value);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static List<CidrBlock> parseBlocks(String config) {
		List<CidrBlock> blocks = new ArrayList<>();
		if (config == null || config.isBlank()) {
			return blocks;
		}
		for (String entry : config.split(",")) {
			CidrBlock block = CidrBlock.parse(entry.trim());
			if (block != null) {
				blocks.add(block);
			}
		}
		return blocks;
	}

	/** A single address or CIDR block, matched by comparing the leading prefix bits. */
	private record CidrBlock(byte[] network, int prefixBits) {

		static CidrBlock parse(String entry) {
			if (entry.isBlank()) {
				return null;
			}
			int slash = entry.lastIndexOf('/');
			String host = slash < 0 ? entry : entry.substring(0, slash);
			InetAddress address = parseLiteral(host);
			if (address == null) {
				return null;
			}
			byte[] network = address.getAddress();
			int bits = network.length * 8;
			if (slash >= 0) {
				try {
					bits = Integer.parseInt(entry.substring(slash + 1).trim());
				} catch (NumberFormatException e) {
					return null;
				}
				if (bits < 0 || bits > network.length * 8) {
					return null;
				}
			}
			return new CidrBlock(network, bits);
		}

		boolean contains(InetAddress candidate) {
			byte[] address = candidate.getAddress();
			// An IPv4 candidate never matches an IPv6 block, or vice versa. Java has already
			// unwrapped IPv4-mapped forms like ::ffff:10.0.0.1 into a 4-byte Inet4Address.
			if (address.length != network.length) {
				return false;
			}
			int fullBytes = prefixBits / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (address[i] != network[i]) {
					return false;
				}
			}
			int remainingBits = prefixBits % 8;
			if (remainingBits == 0) {
				return true;
			}
			int mask = 0xFF << (8 - remainingBits);
			return (address[fullBytes] & mask) == (network[fullBytes] & mask);
		}
	}
}
