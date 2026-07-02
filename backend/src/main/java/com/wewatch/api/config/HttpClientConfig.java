package com.wewatch.api.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

	@Bean
	public RestClientCustomizer timeoutCustomizer(
			@Value("${http.client.connect-timeout-ms:2000}") long connectTimeoutMs,
			@Value("${http.client.read-timeout-ms:5000}") long readTimeoutMs) {
		ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
			.withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
			.withReadTimeout(Duration.ofMillis(readTimeoutMs));
		return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
	}

}
