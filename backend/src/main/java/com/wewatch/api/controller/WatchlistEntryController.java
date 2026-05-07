package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.WatchlistEntryCreateRequest;
import com.wewatch.api.dto.WatchlistEntryResponse;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.service.WatchlistEntryService;

@RestController
@Profile("local")
@RequestMapping("/api/users/{userId}/watchlist")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;

	public WatchlistEntryController(WatchlistEntryService watchlistEntryService) {
		this.watchlistEntryService = watchlistEntryService;
	}

	@PostMapping
	public ResponseEntity<WatchlistEntryResponse> createWatchlistEntry(
		@PathVariable Long userId,
		@Valid @RequestBody WatchlistEntryCreateRequest request
	) {
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
		@RequestParam(required = false) WatchStatus status
	) {
		return watchlistEntryService.findByFilters(userId, status).stream()
			.map(this::toResponse)
			.toList();
	}

	@GetMapping("/{entryId}")
	public WatchlistEntryResponse getWatchlistEntry(@PathVariable Long userId, @PathVariable Long entryId) {
		return toResponse(watchlistEntryService.findById(userId, entryId));
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
