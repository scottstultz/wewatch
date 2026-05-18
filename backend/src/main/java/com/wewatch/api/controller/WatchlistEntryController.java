package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.wewatch.api.model.Title;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.WatchlistEntryService;

@RestController
@RequestMapping("/api/users/{userId}/watchlist")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;

	public WatchlistEntryController(WatchlistEntryService watchlistEntryService, TitleService titleService) {
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
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
		Title createdTitle = titleService.findById(createdEntry.getTitleId());

		return ResponseEntity
			.created(URI.create("/api/users/" + userId + "/watchlist/" + createdEntry.getId()))
			.body(toResponse(createdEntry, createdTitle));
	}

	@GetMapping
	public Page<WatchlistEntryResponse> getWatchlistEntries(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@RequestParam(required = false) WatchStatus status,
		@PageableDefault(size = 20) Pageable pageable
	) {
		requireOwner(userId, authenticatedUser);
		Page<WatchlistEntry> entries = watchlistEntryService.findByFilters(userId, status, pageable);
		List<Long> titleIds = entries.stream().map(WatchlistEntry::getTitleId).collect(Collectors.toList());
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);
		return entries.map(e -> toResponse(e, titlesById.get(e.getTitleId())));
	}

	@GetMapping("/{entryId}")
	public WatchlistEntryResponse getWatchlistEntry(
		@PathVariable Long userId,
		@AuthenticationPrincipal User authenticatedUser,
		@PathVariable Long entryId
	) {
		requireOwner(userId, authenticatedUser);
		WatchlistEntry entry = watchlistEntryService.findById(userId, entryId);
		return toResponse(entry, titleService.findById(entry.getTitleId()));
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
		return toResponse(updated, titleService.findById(updated.getTitleId()));
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

	private WatchlistEntryResponse toResponse(WatchlistEntry entry, Title title) {
		return new WatchlistEntryResponse(
			entry.getId(),
			entry.getUserId(),
			entry.getTitleId(),
			entry.getExternalId(),
			entry.getExternalSource(),
			title != null ? title.getName() : null,
			title != null ? title.getType() : null,
			title != null ? title.getPosterUrl() : null,
			entry.getStatus(),
			entry.getAddedAt(),
			entry.getUpdatedAt(),
			entry.getStartedAt(),
			entry.getCompletedAt()
		);
	}
}
