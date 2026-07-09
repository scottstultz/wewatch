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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// Ratings are self-scoped like dismissals (#268): no watchlist in the route —
// a rating follows the user into every list they belong to. PUT because
// setting a rating is idempotent (re-rating up stays up, rating down replaces
// up); DELETE clears back to unrated.
@RestController
@RequestMapping("/api/titles/{titleId}/rating")
@Tag(name = "Title Ratings", description = "Caller's own thumbs up/down rating on a title, feeding the suggestion taste profile (#273).")
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
	@Operation(summary = "Set the caller's thumbs rating on a title",
		description = "Idempotent — re-submitting the same value is a no-op; submitting the "
			+ "opposite value flips it. Evicts the caller's cached suggestion shelves so the "
			+ "next read recomputes with the updated taste profile (#273).")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Rating set"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "404", description = "No title with this id")
	})
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
	@Operation(summary = "Clear the caller's rating on a title",
		description = "Idempotent — returns 204 even if the caller has no rating on this title, or "
			+ "if titleId doesn't exist at all (a plain delete with no existence check). Evicts the "
			+ "caller's cached suggestion shelves (#273).")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Rating cleared (or none existed)"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public ResponseEntity<Void> clearRating(
		@PathVariable Long titleId,
		@AuthenticationPrincipal User caller
	) {
		titleRatingService.unrate(caller.getId(), titleId);
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}
}
