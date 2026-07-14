package com.wewatch.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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
import com.wewatch.api.exception.InvalidCredentialsException;
import com.wewatch.api.exception.RegistrationNotAllowedException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.security.ClientIpResolver;
import com.wewatch.api.security.EmailNormalizer;
import com.wewatch.api.security.GoogleTokenValidator;
import com.wewatch.api.security.GoogleTokenValidator.GoogleIdentity;
import com.wewatch.api.security.GoogleTokenValidator.InvalidCredentialException;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.LoginAttemptService;
import com.wewatch.api.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Credential exchange for WeWatch JWTs. Sign-in is allowlist-gated.")
@SecurityRequirements
public class AuthController {

	private final GoogleTokenValidator googleTokenValidator;
	private final UserService userService;
	private final JwtTokenService jwtTokenService;
	private final ObjectMapper objectMapper;
	private final AllowedEmailRepository allowedEmailRepository;
	private final LoginAttemptService loginAttemptService;
	private final ClientIpResolver clientIpResolver;

	public AuthController(GoogleTokenValidator googleTokenValidator, UserService userService,
			JwtTokenService jwtTokenService, ObjectMapper objectMapper,
			AllowedEmailRepository allowedEmailRepository, LoginAttemptService loginAttemptService,
			ClientIpResolver clientIpResolver) {
		this.googleTokenValidator = googleTokenValidator;
		this.userService = userService;
		this.jwtTokenService = jwtTokenService;
		this.objectMapper = objectMapper;
		this.allowedEmailRepository = allowedEmailRepository;
		this.loginAttemptService = loginAttemptService;
		this.clientIpResolver = clientIpResolver;
	}

	@PostMapping("/token")
	@Operation(summary = "Exchange a provider credential for a WeWatch JWT",
		description = "Accepts a Google ID token or an email+password credential. The associated "
			+ "email must be present in the allowlist.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Credential accepted; JWT issued"),
		@ApiResponse(responseCode = "400", description = "Unrecognized provider"),
		@ApiResponse(responseCode = "401", description = "Invalid credential"),
		@ApiResponse(responseCode = "403", description = "Email not in the allowlist"),
		@ApiResponse(responseCode = "409", description = "A first-time provider sign-in matched an "
			+ "existing account that already holds credentials; it is not linked (#342)"),
		@ApiResponse(responseCode = "429", description = "Too many attempts; throttled")
	})
	public ResponseEntity<TokenResponse> exchangeToken(@Valid @RequestBody TokenRequest request,
			HttpServletRequest httpRequest) {
		String ip = clientIpResolver.resolve(httpRequest);
		loginAttemptService.checkIp(ip);

		User user;
		if ("google".equals(request.provider())) {
			try {
				GoogleIdentity identity = googleTokenValidator.validate(request.credential());
				requireAllowedEmail(identity.email());
				user = userService.findOrCreateByProviderIdentity(
					request.provider(), identity.sub(), identity.email(), identity.name());
			} catch (InvalidCredentialException | RegistrationNotAllowedException e) {
				loginAttemptService.recordIpFailure(ip);
				throw e;
			}
		} else if ("email".equals(request.provider())) {
			EmailCredential cred = parseEmailCredential(request.credential());
			loginAttemptService.checkEmail(cred.email());
			try {
				requireAllowedEmail(cred.email());
				user = userService.authenticateWithPassword(cred.email(), cred.password());
			} catch (InvalidCredentialsException | RegistrationNotAllowedException e) {
				loginAttemptService.recordEmailFailure(cred.email());
				loginAttemptService.recordIpFailure(ip);
				throw e;
			}
			loginAttemptService.resetEmail(cred.email());
		} else {
			return ResponseEntity.badRequest().build();
		}

		String token = jwtTokenService.generateToken(user);
		return ResponseEntity.ok(new TokenResponse(token));
	}

	@PostMapping("/register")
	@Operation(summary = "Register a new email+password account",
		description = "Email must be present in the allowlist. Returns a WeWatch JWT for the new user.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Account created; JWT issued"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "403", description = "Email not in the allowlist"),
		@ApiResponse(responseCode = "409", description = "Email already registered"),
		@ApiResponse(responseCode = "429", description = "Too many attempts; throttled")
	})
	public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest httpRequest) {
		String ip = clientIpResolver.resolve(httpRequest);
		loginAttemptService.checkIp(ip);
		try {
			requireAllowedEmail(request.email());
		} catch (RegistrationNotAllowedException e) {
			loginAttemptService.recordIpFailure(ip);
			throw e;
		}
		User user = userService.registerWithPassword(
			request.email(), request.displayName(), request.password());
		String token = jwtTokenService.generateToken(user);
		return ResponseEntity.status(HttpStatus.CREATED).body(new TokenResponse(token));
	}

	private void requireAllowedEmail(String email) {
		// The allowlist is the first gate on every auth flow, and it must judge the same string
		// the rest of the request does. existsByEmailIgnoreCase folds case but not surrounding
		// whitespace, so an untrimmed address (the sign-in credential is parsed out of a JSON
		// string and never sees @Email) was answered 403 "not allowed" rather than being matched.
		if (!allowedEmailRepository.existsByEmailIgnoreCase(EmailNormalizer.normalize(email))) {
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
			// Canonical from the edge: this is the one email entry point with no bean validation,
			// so the throttle bucket, the allowlist gate and the user lookup all key off the same
			// form (#345).
			return new EmailCredential(EmailNormalizer.normalize(email), password);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid credential format for email provider");
		}
	}
}
