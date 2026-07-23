package com.wewatch.api.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.TonightPickResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.TonightService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/tonight")
@Tag(name = "Tonight", description = "Watchlist entries that fit the time you actually have.")
public class TonightController {

	private final TonightService tonightService;
	private final WatchlistService watchlistService;

	public TonightController(TonightService tonightService, WatchlistService watchlistService) {
		this.tonightService = tonightService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	@Operation(summary = "List entries that fit a time window",
		description = "Member-only. Returns the watchlist's unfinished entries whose runtime is "
			+ "`maxMinutes` or less, shortest first: a movie is judged on its own runtime, a show on "
			+ "the runtime of the next unwatched episode (episode 1 for a show never started). A title "
			+ "with no known runtime is left out — it cannot be judged to fit. Reads cached data only, "
			+ "so it adds no TMDB traffic.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Fitting entries returned; empty list if none fit"),
		@ApiResponse(responseCode = "400", description = "`maxMinutes` is outside the range 1–600"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of the watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public List<TonightPickResponse> getTonightPicks(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@RequestParam int maxMinutes
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return tonightService.fitsWithin(watchlistId, maxMinutes);
	}
}
