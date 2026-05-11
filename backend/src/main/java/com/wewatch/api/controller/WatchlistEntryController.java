package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.WatchlistEntryCreateRequest;
import com.wewatch.api.dto.WatchlistEntryResponse;
import com.wewatch.api.dto.WatchlistEntryUpdateRequest;
import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.service.WatchlistEntryService;

@RestController
@RequestMapping("/api/users/{userId}/watchlist")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;

	public WatchlistEntryController(WatchlistEntryService watchlistEntryService) {
		this.watchlistEntryService = watchlistEntryService;
	}

	@PostMapping
	public ResponseEntity<WatchlistEntryResponse> createWatchlistEntry(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@Valid @RequestBody WatchlistEntryCreateRequest request
	) {
		requireOwner(userId, authenticatedUser);
		WatchlistEntry createdEntry = watchlistEntryService.create(new WatchlistEntry(
			null,
			userId,
			request.titleId(),
			request.status(),
			null,
			null,
			null,
			null
		));

		return ResponseEntity
			.created(URI.create("/api/users/" + userId + "/watchlist/" + createdEntry.getId()))
			.body(toResponse(createdEntry));
	}

	@GetMapping
	public List<WatchlistEntryResponse> getWatchlistEntries(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@RequestParam(required = false) WatchStatus status
	) {
		requireOwner(userId, authenticatedUser);
		return watchlistEntryService.findByFilters(userId, status).stream()
			.map(this::toResponse)
			.toList();
	}

	@GetMapping("/{entryId}")
	public WatchlistEntryResponse getWatchlistEntry(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@PathVariable Long entryId
	) {
		requireOwner(userId, authenticatedUser);
		return toResponse(watchlistEntryService.findById(userId, entryId));
	}

	@PatchMapping("/{entryId}")
	public WatchlistEntryResponse updateWatchlistEntry(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@PathVariable Long entryId,
		@Valid @RequestBody WatchlistEntryUpdateRequest request
	) {
		requireOwner(userId, authenticatedUser);
		WatchlistEntry updated = watchlistEntryService.update(userId, entryId, new WatchlistEntry(
			null,
			userId,
			null,
			request.status(),
			null,
			null,
			null,
			null
		));
		return toResponse(updated);
	}

	@DeleteMapping("/{entryId}")
	public ResponseEntity<Void> deleteWatchlistEntry(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@PathVariable Long entryId
	) {
		requireOwner(userId, authenticatedUser);
		watchlistEntryService.deleteById(userId, entryId);
		return ResponseEntity.noContent().build();
	}

	private void requireOwner(Long userId, User authenticatedUser) {
		if (!authenticatedUser.getId().equals(userId)) {
			throw new ForbiddenException("Access denied");
		}
	}

	private WatchlistEntryResponse toResponse(WatchlistEntry watchlistEntry) {
		return new WatchlistEntryResponse(
			watchlistEntry.getId(),
			watchlistEntry.getUserId(),
			watchlistEntry.getTitleId(),
			watchlistEntry.getStatus(),
			watchlistEntry.getAddedAt(),
			watchlistEntry.getUpdatedAt(),
			watchlistEntry.getStartedAt(),
			watchlistEntry.getCompletedAt()
		);
	}
}
