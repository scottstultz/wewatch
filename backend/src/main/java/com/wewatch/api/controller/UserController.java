package com.wewatch.api.controller;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.UserCreateRequest;
import com.wewatch.api.dto.UserResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.UserService;

@RestController
@Profile("local")
@RequestMapping("/api/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping
	public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
		User createdUser = userService.create(new User(null, request.email(), request.displayName(), null, null));

		return ResponseEntity
			.created(URI.create("/api/users/" + createdUser.getId()))
			.body(toResponse(createdUser));
	}

	@GetMapping("/{userId}")
	public UserResponse getUser(@PathVariable Long userId) {
		return toResponse(userService.findById(userId));
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
