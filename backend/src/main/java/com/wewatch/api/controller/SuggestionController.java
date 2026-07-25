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
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.service.GenreBrowseService;
import com.wewatch.api.service.SuggestionDismissalService;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/suggestions")
@Tag(name = "Suggestions", description = "Personalized suggestion shelves for a watchlist, and per-user dismissals.")
public class SuggestionController {

	private final SuggestionService suggestionService;
	private final SuggestionDismissalService suggestionDismissalService;
	private final GenreBrowseService genreBrowseService;
	private final WatchlistService watchlistService;

	public SuggestionController(
		SuggestionService suggestionService,
		SuggestionDismissalService suggestionDismissalService,
		GenreBrowseService genreBrowseService,
		WatchlistService watchlistService
	) {
		this.suggestionService = suggestionService;
		this.suggestionDismissalService = suggestionDismissalService;
		this.genreBrowseService = genreBrowseService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	@Operation(summary = "Get personalized suggestion shelves for a watchlist",
		description = "Builds \"top picks\" shelves from the members' taste profile (genre, cast/"
			+ "director, keyword, and franchise-continuation signals), streaming-provider "
			+ "availability, and TMDB discover/trending pools. Excludes titles already on the "
			+ "watchlist and titles any member has dismissed. Caller must be a member of the "
			+ "watchlist.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Suggestion shelves returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found"),
		@ApiResponse(responseCode = "502", description = "TMDB API request failed")
	})
	public ResponseEntity<List<SuggestionShelfResponse>> getSuggestions(
		@RequestParam Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return ResponseEntity.ok(suggestionService.topPicks(watchlistId));
	}

	@GetMapping("/browse")
	@Operation(summary = "Browse TMDB by genre, ranked for a watchlist",
		description = "Member-only. Returns one page (20) of titles carrying **every** given genre "
			+ "(TMDB's AND semantics), ranked against the watchlist members' taste profile and with "
			+ "titles already on the list — or dismissed, or thumbs-downed by any member — removed. "
			+ "Results carry streaming-availability badges but are **not** filtered by provider: two "
			+ "AND-ed genres narrow hard enough on their own. Genre ids are medium-specific, so "
			+ "`type` selects which catalog they are read against (see GET /api/genres).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "One ranked page; empty when nothing matches"),
		@ApiResponse(responseCode = "400", description = "No genres given, or `page` outside 1–6"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found"),
		@ApiResponse(responseCode = "502", description = "TMDB API request failed")
	})
	public ResponseEntity<List<TitleSearchResponse>> browseByGenre(
		@RequestParam Long watchlistId,
		// A query param, not a path variable: a {type} path variable receives
		// lowercase movie/tv and misses the case-sensitive TitleType binding — the
		// same shape /titles/recommendations and /titles/detail take (#358).
		@RequestParam TitleType type,
		@RequestParam List<Integer> genres,
		@RequestParam(defaultValue = "1") int page,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return ResponseEntity.ok(genreBrowseService.browse(watchlistId, type, genres, page));
	}

	// Dismissals are self-scoped (#268): no watchlist in the route — the exclusion
	// follows the user into every list they belong to. Eviction lets the next
	// suggestions read recompute without the dismissed title.
	@PostMapping("/dismissals")
	@Operation(summary = "Dismiss a suggested title",
		description = "Dismissal is scoped to the caller, not to a single watchlist — the title is "
			+ "excluded from the caller's suggestions across every watchlist they belong to. "
			+ "Evicts the caller's cached suggestions so the next read recomputes without the "
			+ "dismissed title.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Title dismissed"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public ResponseEntity<Void> dismiss(
		@Valid @RequestBody SuggestionDismissalRequest request,
		@AuthenticationPrincipal User caller
	) {
		suggestionDismissalService.dismiss(caller.getId(), request.tmdbId());
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/dismissals/{tmdbId}")
	@Operation(summary = "Undo a dismissal",
		description = "Removes the caller's dismissal of the given title, if one exists, and evicts "
			+ "the caller's cached suggestions so the title becomes eligible again. Idempotent — "
			+ "succeeds even if the title was never dismissed.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Dismissal removed (or none existed)"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public ResponseEntity<Void> undoDismissal(
		@PathVariable String tmdbId,
		@AuthenticationPrincipal User caller
	) {
		suggestionDismissalService.undismiss(caller.getId(), tmdbId);
		suggestionService.evictForUser(caller.getId());
		return ResponseEntity.noContent().build();
	}
}
