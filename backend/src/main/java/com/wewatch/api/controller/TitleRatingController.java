package com.wewatch.api.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.TitleRatingRequest;
import com.wewatch.api.model.User;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.TitleRatingService;

// Ratings are self-scoped like dismissals (#268): no watchlist in the route —
// a rating follows the user into every list they belong to. PUT because
// setting a rating is idempotent (re-rating up stays up, rating down replaces
// up); DELETE clears back to unrated.
@RestController
@RequestMapping("/api/titles/{titleId}/rating")
public class TitleRatingController {

	private final TitleRatingService titleRatingService;
	private final SuggestionService suggestionService;

	public TitleRatingController(TitleRatingService titleRatingService, SuggestionService suggestionService) {
		this.titleRatingService = titleRatingService;
		this.suggestionService = suggestionService;
	}

	// A rating change shifts the taste profile of every list the caller
	// belongs to, so evict their cached shelves and let the next read
	// recompute lazily — the dismissal pattern (#268)
	@PutMapping
	public ResponseEntity<Void> rate(
		@PathVariable Long titleId,
		@Valid @RequestBody TitleRatingRequest request,
		@AuthenticationPrincipal User caller
	) {
		titleRatingService.rate(caller.getId(), titleId, request.rating());
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> clearRating(
		@PathVariable Long titleId,
		@AuthenticationPrincipal User caller
	) {
		titleRatingService.unrate(caller.getId(), titleId);
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}
}
