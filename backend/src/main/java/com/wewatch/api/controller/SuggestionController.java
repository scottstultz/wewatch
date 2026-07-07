package com.wewatch.api.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.SuggestionDismissalRequest;
import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.SuggestionDismissalService;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/suggestions")
public class SuggestionController {

	private final SuggestionService suggestionService;
	private final SuggestionDismissalService suggestionDismissalService;
	private final WatchlistService watchlistService;

	public SuggestionController(
		SuggestionService suggestionService,
		SuggestionDismissalService suggestionDismissalService,
		WatchlistService watchlistService
	) {
		this.suggestionService = suggestionService;
		this.suggestionDismissalService = suggestionDismissalService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	public ResponseEntity<List<SuggestionShelfResponse>> getSuggestions(
		@RequestParam Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return ResponseEntity.ok(suggestionService.topPicks(watchlistId));
	}

	// Dismissals are self-scoped (#268): no watchlist in the route — the exclusion
	// follows the user into every list they belong to. Eviction lets the next
	// suggestions read recompute without the dismissed title.
	@PostMapping("/dismissals")
	public ResponseEntity<Void> dismiss(
		@Valid @RequestBody SuggestionDismissalRequest request,
		@AuthenticationPrincipal User caller
	) {
		suggestionDismissalService.dismiss(caller.getId(), request.tmdbId());
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/dismissals/{tmdbId}")
	public ResponseEntity<Void> undoDismissal(
		@PathVariable String tmdbId,
		@AuthenticationPrincipal User caller
	) {
		suggestionDismissalService.undismiss(caller.getId(), tmdbId);
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}
}
