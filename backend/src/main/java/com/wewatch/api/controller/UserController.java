package com.wewatch.api.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.wewatch.api.dto.UserResponse;
import com.wewatch.api.dto.UserUpdateRequest;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.exception.RegistrationNotAllowedException;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.AllowedEmailRepository;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "User profile and streaming-provider settings.")
public class UserController {

	private final UserService userService;
	private final AllowedEmailRepository allowedEmailRepository;
	private final SuggestionService suggestionService;

	public UserController(UserService userService, AllowedEmailRepository allowedEmailRepository,
			SuggestionService suggestionService) {
		this.userService = userService;
		this.allowedEmailRepository = allowedEmailRepository;
		this.suggestionService = suggestionService;
	}

	@GetMapping("/me")
	@Operation(summary = "Get the current authenticated user's profile")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Profile returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public UserResponse getCurrentUser(@AuthenticationPrincipal User authenticatedUser) {
		return toResponse(authenticatedUser);
	}

	@GetMapping("/{userId}")
	@Operation(summary = "Get a user's profile",
		description = "Self-only — the caller may only fetch their own profile.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Profile returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not the requested user")
	})
	public UserResponse getUser(@PathVariable Long userId, @AuthenticationPrincipal User caller) {
		if (!caller.getId().equals(userId)) {
			throw new ForbiddenException("Cannot view another user's profile");
		}
		return toResponse(caller);
	}

	@PatchMapping("/{userId}")
	@Operation(summary = "Update a user's profile or streaming-provider settings",
		description = "Self-only — the caller may only update their own profile. Updating "
			+ "watchRegion or watchProviderIds evicts the caller's cached suggestion shelves "
			+ "so the next read recomputes with the new provider context (#270).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Profile updated"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not the requested user, or the new "
			+ "email is not in the allowlist")
	})
	public UserResponse updateUser(
		@PathVariable Long userId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody UserUpdateRequest request
	) {
		if (!caller.getId().equals(userId)) {
			throw new ForbiddenException("Cannot update another user's profile");
		}
		if (request.email() != null
				&& !request.email().equalsIgnoreCase(caller.getEmail())
				&& !allowedEmailRepository.existsByEmailIgnoreCase(request.email())) {
			throw new RegistrationNotAllowedException();
		}
		User updated = userService.update(userId, request.email(), request.displayName());
		// Streaming-service settings (#270) change what every list the user
		// belongs to may suggest — evict those cached shelves so the next read
		// recomputes with the new provider context (same pattern as dismissals).
		if (request.watchRegion() != null || request.watchProviderIds() != null) {
			updated = userService.updateStreamingSettings(userId, request.watchRegion(), request.watchProviderIds());
			suggestionService.evictForUser(userId);
		}
		return toResponse(updated);
	}

	private UserResponse toResponse(User user) {
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getDisplayName(),
			user.getCreatedAt(),
			user.getUpdatedAt(),
			user.getWatchRegion(),
			user.getWatchProviderIds()
		);
	}
}
