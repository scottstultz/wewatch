package com.wewatch.api.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.ReturningEpisodeResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.ReturningEpisodeService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/returning")
@Tag(name = "Returning", description = "Shows on a watchlist with a new episode airing soon.")
public class ReturningEpisodeController {

	private final ReturningEpisodeService returningEpisodeService;
	private final WatchlistService watchlistService;

	public ReturningEpisodeController(
		ReturningEpisodeService returningEpisodeService,
		WatchlistService watchlistService
	) {
		this.returningEpisodeService = returningEpisodeService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	@Operation(summary = "List shows with an episode airing soon",
		description = "Returns the watchlist's TV entries in WATCHING status whose next episode airs "
			+ "within `days` of today (both ends inclusive), soonest first, one row per show. Reads "
			+ "cached episode data only, so it adds no TMDB traffic. Independent of watch progress: a "
			+ "show you are behind on still appears, because the point is to tell you it is back.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Upcoming episodes returned; empty list if none"),
		@ApiResponse(responseCode = "400", description = "`days` is outside the range 1–30"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public List<ReturningEpisodeResponse> getReturning(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@RequestParam(defaultValue = "${app.returning.default-days:7}") int days
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return returningEpisodeService.returningWithin(watchlistId, days);
	}
}
