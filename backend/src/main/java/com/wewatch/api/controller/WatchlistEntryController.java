package com.wewatch.api.controller;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.WatchlistEntryCreateRequest;
import com.wewatch.api.dto.WatchlistEntryResponse;
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
