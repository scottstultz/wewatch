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
import com.wewatch.api.service.UserService;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService userService;
	private final AllowedEmailRepository allowedEmailRepository;

	public UserController(UserService userService, AllowedEmailRepository allowedEmailRepository) {
		this.userService = userService;
		this.allowedEmailRepository = allowedEmailRepository;
	}

	@GetMapping("/me")
	public UserResponse getCurrentUser(@AuthenticationPrincipal User authenticatedUser) {
		return toResponse(authenticatedUser);
	}

	@GetMapping("/{userId}")
	public UserResponse getUser(@PathVariable Long userId, @AuthenticationPrincipal User caller) {
		if (!caller.getId().equals(userId)) {
			throw new ForbiddenException("Cannot view another user's profile");
		}
		return toResponse(caller);
	}

	@PatchMapping("/{userId}")
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
		return toResponse(userService.update(userId, request.email(), request.displayName()));
	}

	private UserResponse toResponse(User user) {
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getDisplayName(),
			user.getCreatedAt(),
			user.getUpdatedAt()
		);
	}
}
