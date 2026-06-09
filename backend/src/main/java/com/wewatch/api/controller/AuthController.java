package com.wewatch.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.RegisterRequest;
import com.wewatch.api.dto.TokenRequest;
import com.wewatch.api.dto.TokenResponse;
import com.wewatch.api.exception.RegistrationNotAllowedException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.security.GoogleTokenValidator;
import com.wewatch.api.security.GoogleTokenValidator.GoogleIdentity;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.service.UserService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final GoogleTokenValidator googleTokenValidator;
	private final UserService userService;
	private final JwtTokenService jwtTokenService;
	private final ObjectMapper objectMapper;
	private final AllowedEmailRepository allowedEmailRepository;

	public AuthController(GoogleTokenValidator googleTokenValidator, UserService userService,
			JwtTokenService jwtTokenService, ObjectMapper objectMapper,
			AllowedEmailRepository allowedEmailRepository) {
		this.googleTokenValidator = googleTokenValidator;
		this.userService = userService;
		this.jwtTokenService = jwtTokenService;
		this.objectMapper = objectMapper;
		this.allowedEmailRepository = allowedEmailRepository;
	}

	@PostMapping("/token")
	public ResponseEntity<TokenResponse> exchangeToken(@Valid @RequestBody TokenRequest request) {
		User user;
		if ("google".equals(request.provider())) {
			GoogleIdentity identity = googleTokenValidator.validate(request.credential());
			requireAllowedEmail(identity.email());
			user = userService.findOrCreateByProviderIdentity(
				request.provider(), identity.sub(), identity.email(), identity.name());
		} else if ("email".equals(request.provider())) {
			EmailCredential cred = parseEmailCredential(request.credential());
			requireAllowedEmail(cred.email());
			user = userService.authenticateWithPassword(cred.email(), cred.password());
		} else {
			return ResponseEntity.badRequest().build();
		}

		String token = jwtTokenService.generateToken(user);
		return ResponseEntity.ok(new TokenResponse(token));
	}

	@PostMapping("/register")
	public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
		requireAllowedEmail(request.email());
		User user = userService.registerWithPassword(
			request.email(), request.displayName(), request.password());
		String token = jwtTokenService.generateToken(user);
		return ResponseEntity.status(HttpStatus.CREATED).body(new TokenResponse(token));
	}

	private void requireAllowedEmail(String email) {
		if (!allowedEmailRepository.existsByEmail(email.toLowerCase())) {
			throw new RegistrationNotAllowedException();
		}
	}

	private record EmailCredential(String email, String password) {}

	private EmailCredential parseEmailCredential(String credential) {
		try {
			JsonNode node = objectMapper.readTree(credential);
			String email = node.has("email") ? node.get("email").asText() : null;
			String password = node.has("password") ? node.get("password").asText() : null;
			if (email == null || email.isBlank() || password == null || password.isBlank()) {
				throw new IllegalArgumentException("Email and password are required");
			}
			return new EmailCredential(email, password);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid credential format for email provider");
		}
	}
}
