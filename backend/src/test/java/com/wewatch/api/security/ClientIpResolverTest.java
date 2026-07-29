package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

	/** Loopback + RFC 1918, as shipped. {@code theShippedDefault…} guards this against drift. */
	private static final String DEFAULT_TRUSTED = "127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

	private static MockHttpServletRequest request(String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		return request;
	}

	// ── Untrusted peer: the headers must not be believed ──────────────────

	@Test
	void directCallerWithNoHeadersResolvesToThePeerAddress() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);

		assertThat(resolver.resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
	}

	@Test
	void headersFromAnUntrustedPeerAreIgnored() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("203.0.113.7");
		request.addHeader("X-Forwarded-For", "198.51.100.9");
		request.addHeader("X-Real-IP", "198.51.100.9");

		// A caller reaching the service directly could otherwise mint a new bucket key per
		// request and never fill one, which is the whole point of the peer check.
		assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
	}

	@Test
	void anEmptyTrustedProxyListTrustsNoHeadersAtAll() {
		ClientIpResolver resolver = new ClientIpResolver("");
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("172.18.0.2");
	}

	// ── Trusted peer: X-Forwarded-For, right to left ──────────────────────

	@Test
	void trustedProxyForwardsTheClientAddress() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void matchingForwardedForAndRealIpFromATrustedProxyResolveToTheClient() {
		// The production shape after #408: nginx now sets X-Forwarded-For and X-Real-IP to the
		// SAME single resolved value, rather than X-Real-IP alone. XFF is checked first, so this
		// pins that the two headers agreeing doesn't confuse the walk — an X-Real-IP-only test
		// wouldn't exercise the path production actually takes.
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9");
		request.addHeader("X-Real-IP", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void aClientInjectedForwardedForPrefixIsIgnored() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		// nginx appends the real peer to whatever the client sent, so a client that forges
		// the header lands to the LEFT of its own address. Reading left-to-right would hand
		// it a bucket key of its choosing.
		request.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void trustedHopsInTheChainAreSkipped() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9, 10.1.2.3, 192.168.5.5");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void portSuffixesAreStripped() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9:51423");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void malformedForwardedForEntriesAreSkipped() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.9, unknown");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void ipv6ClientsAreResolvedAndUnbracketed() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "[2001:db8::1]:443");

		assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
	}

	// ── Trusted peer: fallbacks ──────────────────────────────────────────

	@Test
	void xRealIpIsUsedWhenForwardedForIsAbsent() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Real-IP", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void aTrustedPeerSendingNoHeadersFallsBackToItsOwnAddress() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);

		// Not a proxied request at all — a health check or a container-local caller.
		assertThat(resolver.resolve(request("172.18.0.2"))).isEqualTo("172.18.0.2");
	}

	@Test
	void aChainOfOnlyTrustedHopsFallsBackRatherThanReturningAProxy() {
		ClientIpResolver resolver = new ClientIpResolver(DEFAULT_TRUSTED);
		MockHttpServletRequest request = request("172.18.0.2");
		request.addHeader("X-Forwarded-For", "10.1.2.3");

		assertThat(resolver.resolve(request)).isEqualTo("172.18.0.2");
	}

	// ── Trusted-proxy matching ───────────────────────────────────────────

	@Test
	void cidrBoundsAreRespected() {
		ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");
		MockHttpServletRequest inside = request("172.31.255.254");
		inside.addHeader("X-Forwarded-For", "198.51.100.9");
		// 172.32.0.1 is one address past the end of 172.16.0.0/12.
		MockHttpServletRequest outside = request("172.32.0.1");
		outside.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(inside)).isEqualTo("198.51.100.9");
		assertThat(resolver.resolve(outside)).isEqualTo("172.32.0.1");
	}

	@Test
	void aBareAddressIsTrustedAsASingleHost() {
		ClientIpResolver resolver = new ClientIpResolver("172.18.0.2");
		MockHttpServletRequest proxy = request("172.18.0.2");
		proxy.addHeader("X-Forwarded-For", "198.51.100.9");
		MockHttpServletRequest neighbour = request("172.18.0.3");
		neighbour.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(proxy)).isEqualTo("198.51.100.9");
		assertThat(resolver.resolve(neighbour)).isEqualTo("172.18.0.3");
	}

	@Test
	void anIpv6ProxyIsTrustedByCidr() {
		ClientIpResolver resolver = new ClientIpResolver("::1/128");
		MockHttpServletRequest request = request("0:0:0:0:0:0:0:1");
		request.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void anIpv4PeerDoesNotMatchAnIpv6Block() {
		ClientIpResolver resolver = new ClientIpResolver("::/0");
		MockHttpServletRequest request = request("10.0.0.1");
		request.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
	}

	@Test
	void unparseableTrustedProxyEntriesAreDiscardedNotFatal() {
		ClientIpResolver resolver = new ClientIpResolver("not-an-ip, 10.0.0.0/99, ,10.0.0.0/8");
		MockHttpServletRequest request = request("10.1.2.3");
		request.addHeader("X-Forwarded-For", "198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	/**
	 * The value that actually ships, not the copy above. A typo in application.properties
	 * fails safe — nothing is trusted — so it would leave #336 unfixed in the deployed
	 * topology without failing any other test.
	 */
	@Test
	void theShippedDefaultTrustsTheDockerBridgeProxyAndNothingPublic() throws Exception {
		Properties properties = new Properties();
		try (InputStream in = ClientIpResolver.class.getResourceAsStream("/application.properties")) {
			properties.load(in);
		}
		String configured = properties.getProperty("app.auth.throttle.trusted-proxies");
		assertThat(configured).isNotBlank();

		ClientIpResolver resolver = new ClientIpResolver(configured);
		MockHttpServletRequest fromNginx = request("172.18.0.2");
		fromNginx.addHeader("X-Forwarded-For", "198.51.100.9");
		MockHttpServletRequest fromTheInternet = request("198.51.100.9");
		fromTheInternet.addHeader("X-Forwarded-For", "1.1.1.1");

		assertThat(resolver.resolve(fromNginx)).isEqualTo("198.51.100.9");
		assertThat(resolver.resolve(fromTheInternet)).isEqualTo("198.51.100.9");
	}
}
