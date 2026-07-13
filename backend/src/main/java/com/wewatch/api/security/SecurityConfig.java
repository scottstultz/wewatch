package com.wewatch.api.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.wewatch.api.filter.RequestCorrelationFilter;
import com.wewatch.api.service.UserService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Value("${app.cors.allowed-origins}")
	private String allowedOrigins;

	// springdoc's own enable flag, so the docs paths are only permitAll when the docs
	// beans actually register — one switch, and the two can't drift apart (#343). The
	// :false default is the point: an unresolvable property fails closed. Gating on
	// api-docs alone is deliberate; Swagger UI is useless without the spec.
	@Value("${springdoc.api-docs.enabled:false}")
	private boolean apiDocsEnabled;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, WeWatchJwtAuthenticationConverter converter,
			RequestCorrelationFilter requestCorrelationFilter, TokenRefreshFilter tokenRefreshFilter)
			throws Exception {
		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			.csrf(AbstractHttpConfigurer::disable)
			.addFilterBefore(requestCorrelationFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(tokenRefreshFilter, BearerTokenAuthenticationFilter.class)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/api/health", "/api/auth/**").permitAll();
				if (apiDocsEnabled) {
					// /v3/api-docs.yaml is a sibling path, not under /v3/api-docs/**.
					// Swagger UI's webjar assets are served under /swagger-ui/**.
					auth.requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml",
						"/swagger-ui/**", "/swagger-ui.html").permitAll();
				}
				auth.anyRequest().authenticated();
			})
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(this::sendUnauthorized)
				.accessDeniedHandler(this::sendForbidden)
			)
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
				.authenticationEntryPoint(this::sendUnauthorized)
			);
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
		config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", TokenRefreshFilter.REFRESHED_TOKEN_HEADER));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	public WeWatchJwtAuthenticationConverter weWatchJwtAuthenticationConverter(UserService userService) {
		return new WeWatchJwtAuthenticationConverter(userService);
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public JwtDecoder jwtDecoder(JwtTokenService jwtTokenService) {
		return jwtTokenService.jwtDecoder();
	}

	private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
		throws IOException {
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
	}

	private void sendForbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
		throws IOException {
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
	}
}
