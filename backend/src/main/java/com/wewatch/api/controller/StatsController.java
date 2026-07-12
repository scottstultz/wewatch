package com.wewatch.api.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.StatsResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.StatsService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/stats")
@Tag(name = "Stats", description = "What a watchlist has watched: counts, time and genres.")
public class StatsController {

	private final StatsService statsService;
	private final WatchlistService watchlistService;

	public StatsController(StatsService statsService, WatchlistService watchlistService) {
		this.statsService = statsService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	@Operation(summary = "Watch stats for a watchlist",
		description = "Totals for the watchlist: movies and shows finished, episodes watched, watch "
			+ "time summed from cached runtimes, and watch time per genre. Computed entirely from "
			+ "cached data, so it adds no TMDB traffic per view.\n\n"
			+ "Scoped to the watchlist, not the caller: episode progress is stored per entry and has "
			+ "no user column, so on a shared list these are the household's numbers, not yours "
			+ "alone.\n\n"
			+ "Titles and episodes whose runtime is not cached still count toward the counts but add "
			+ "no time; `itemsMissingRuntime` says how many, so a caller can tell how complete the "
			+ "time totals are.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Stats returned; all-zero for an empty watchlist"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public StatsResponse getStats(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return statsService.statsFor(watchlistId);
	}
}
