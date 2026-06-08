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
import com.wewatch.api.model.Title;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.WatchlistEntryService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/entries")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;
	private final WatchlistService watchlistService;

	public WatchlistEntryController(
		WatchlistEntryService watchlistEntryService,
		TitleService titleService,
		WatchlistService watchlistService
	) {
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
		this.watchlistService = watchlistService;
	}

	@PostMapping
	public ResponseEntity<WatchlistEntryResponse> createWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody WatchlistEntryCreateRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		WatchlistEntry entry = new WatchlistEntry(
			null,
			watchlistId,
			request.titleId(),
			request.status(),
			null,
			null,
			null,
			null
		);
		entry.setAddedByUserId(caller.getId());
		WatchlistEntry created = watchlistEntryService.create(entry);
		Title title = titleService.findById(created.getTitleId());
		return ResponseEntity
			.created(URI.create("/api/watchlists/" + watchlistId + "/entries/" + created.getId()))
			.body(toResponse(created, title));
	}

	@GetMapping
	public Page<WatchlistEntryResponse> getWatchlistEntries(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@RequestParam(required = false) WatchStatus status,
		@PageableDefault(size = 20) Pageable pageable
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		Page<WatchlistEntry> entries = watchlistEntryService.findByFilters(watchlistId, status, pageable);
		List<Long> titleIds = entries.stream().map(WatchlistEntry::getTitleId).collect(Collectors.toList());
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);
		return entries.map(e -> toResponse(e, titlesById.get(e.getTitleId())));
	}

	@GetMapping("/{entryId}")
	public WatchlistEntryResponse getWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		return toResponse(entry, titleService.findById(entry.getTitleId()));
	}

	@PatchMapping("/{entryId}")
	public WatchlistEntryResponse updateWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId,
		@Valid @RequestBody WatchlistEntryUpdateRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		WatchlistEntry updated = watchlistEntryService.update(watchlistId, entryId, new WatchlistEntry(
			null,
			watchlistId,
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
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		watchlistEntryService.deleteById(watchlistId, entryId);
		return ResponseEntity.noContent().build();
	}

	private WatchlistEntryResponse toResponse(WatchlistEntry entry, Title title) {
		return new WatchlistEntryResponse(
			entry.getId(),
			entry.getWatchlistId(),
			entry.getAddedByUserId(),
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
