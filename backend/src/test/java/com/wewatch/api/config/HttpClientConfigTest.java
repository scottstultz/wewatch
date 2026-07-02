package com.wewatch.api.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpClientConfigTest {

	@Test
	void customizedClientTimesOutInsteadOfHangingOnUnresponsiveServer() throws IOException {
		RestClientCustomizer customizer = new HttpClientConfig().timeoutCustomizer(1000, 250);

		// A ServerSocket that is never accept()ed: connections complete via the OS
		// backlog but no response is ever written, so only the read timeout can
		// unblock the client.
		try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			RestClient.Builder builder = RestClient.builder()
				.baseUrl("http://localhost:" + serverSocket.getLocalPort());
			customizer.customize(builder);
			RestClient client = builder.build();

			assertThatThrownBy(() -> client.get().uri("/hang").retrieve().body(String.class))
				.isInstanceOf(ResourceAccessException.class)
				.hasCauseInstanceOf(IOException.class);
		}
	}

}
