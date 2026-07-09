package com.wewatch.api.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.BulkSeasonRequest;
import com.wewatch.api.dto.EpisodeProgressResponse;
import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.User;
import com.wewatch.api.service.EpisodeProgressService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/entries/{entryId}/episodes")
@Tag(name = "Episode Progress", description = "Per-episode watched tracking for TV show entries on a watchlist.")
public class EpisodeProgressController {

	private final EpisodeProgressService episodeProgressService;
	private final WatchlistService watchlistService;

	public EpisodeProgressController(
		EpisodeProgressService episodeProgressService,
		WatchlistService watchlistService
	) {
		this.episodeProgressService = episodeProgressService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	@Operation(summary = "Get episode watch progress for a watchlist entry",
		description = "Returns one row per episode with progress tracked so far. Pass `season` to "
			+ "restrict to a single season number; omit it to return progress across all seasons. "
			+ "Only valid for entries whose title is a TV show.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Progress returned"),
		@ApiResponse(responseCode = "400", description = "The entry's title is not a TV show"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist or entry not found")
	})
	public List<EpisodeProgressResponse> getProgress(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@AuthenticationPrincipal User caller,
		@RequestParam(required = false) Integer season
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return episodeProgressService.getProgress(watchlistId, entryId, season)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	@PatchMapping("/{seasonNumber}/{episodeNumber}")
	@Operation(summary = "Toggle a single episode's watched state",
		description = "Flips watched to unwatched or vice versa, creating the progress row if it "
			+ "doesn't exist yet. Marking an episode watched is rejected if the episode has not "
			+ "aired yet, per the TMDB episode cache's air date (episodes with no cached air date "
			+ "are treated as aired). Requires editor or owner role on the watchlist.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Episode toggled"),
		@ApiResponse(responseCode = "400", description = "The entry's title is not a TV show, or the episode has not aired yet"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist, or has viewer-only access"),
		@ApiResponse(responseCode = "404", description = "Watchlist or entry not found")
	})
	public EpisodeProgressResponse toggleEpisode(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@PathVariable int seasonNumber,
		@PathVariable int episodeNumber,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		EpisodeProgress progress = episodeProgressService.toggleEpisode(
			watchlistId, entryId, seasonNumber, episodeNumber
		);
		return toResponse(progress);
	}

	@PutMapping("/{seasonNumber}")
	@Operation(summary = "Bulk mark a season's episodes watched or unwatched",
		description = "Sets the watched state for the given episode numbers within the season in "
			+ "one call, creating progress rows as needed. When marking watched, any episode numbers "
			+ "that have not aired yet (per the TMDB episode cache) are silently excluded from the "
			+ "update rather than rejected. Requires editor or owner role on the watchlist.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Season progress updated; returns the season's episodes"),
		@ApiResponse(responseCode = "400", description = "The entry's title is not a TV show, or the request body failed validation"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist, or has viewer-only access"),
		@ApiResponse(responseCode = "404", description = "Watchlist or entry not found")
	})
	public List<EpisodeProgressResponse> bulkMarkSeason(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@PathVariable int seasonNumber,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody BulkSeasonRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		return episodeProgressService.bulkMarkSeason(
			watchlistId, entryId, seasonNumber, request.watched(), request.episodeNumbers()
		)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	private EpisodeProgressResponse toResponse(EpisodeProgress progress) {
		return new EpisodeProgressResponse(
			progress.getId(),
			progress.getWatchlistEntryId(),
			progress.getSeasonNumber(),
			progress.getEpisodeNumber(),
			progress.getWatched(),
			progress.getWatchedAt()
		);
	}
}
