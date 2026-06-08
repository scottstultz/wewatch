package com.wewatch.api.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.TokenRequest;
import com.wewatch.api.dto.TokenResponse;
import com.wewatch.api.model.User;
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

	public AuthController(GoogleTokenValidator googleTokenValidator, UserService userService,
			JwtTokenService jwtTokenService) {
		this.googleTokenValidator = googleTokenValidator;
		this.userService = userService;
		this.jwtTokenService = jwtTokenService;
	}

	@PostMapping("/token")
	public ResponseEntity<TokenResponse> exchangeToken(@Valid @RequestBody TokenRequest request) {
		if (!"google".equals(request.provider())) {
			return ResponseEntity.badRequest().build();
		}

		GoogleIdentity identity = googleTokenValidator.validate(request.credential());
		User user = userService.findOrCreateByProviderIdentity(
			request.provider(), identity.sub(), identity.email(), identity.name());
		String token = jwtTokenService.generateToken(user);
		return ResponseEntity.ok(new TokenResponse(token));
	}
}
