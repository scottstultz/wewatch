package com.wewatch.api.controller;

import java.util.List;

import jakarta.validation.constraints.Pattern;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.dto.WatchRegionResponse;
import com.wewatch.api.service.WatchProviderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// Read-only streaming-service catalog (#270): the settings picker loads
// regions and the per-region provider list from here; Discover uses the same
// provider list to resolve badge ids to names/logos.
@RestController
@Validated
@RequestMapping("/api/watch-providers")
@Tag(name = "Watch Providers", description = "Read-only TMDB/JustWatch streaming-provider catalog, used by the settings picker and availability badges.")
public class WatchProviderController {

	private final WatchProviderService watchProviderService;

	public WatchProviderController(WatchProviderService watchProviderService) {
		this.watchProviderService = watchProviderService;
	}

	@GetMapping
	@Operation(summary = "List streaming providers available in a region",
		description = "Merges TMDB's movie and TV provider catalogs for the region, deduplicated "
			+ "by provider id. `region` is a two-letter ISO 3166-1 region code (e.g. \"US\"), "
			+ "case-insensitive. Results are cached per region.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Providers returned (empty list if the region has none)"),
		@ApiResponse(responseCode = "400", description = "region is missing or not a 2-letter code"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API request failed")
	})
	public List<WatchProviderResponse> getProviders(
		@RequestParam @Pattern(regexp = "[A-Za-z]{2}") String region
	) {
		return watchProviderService.providersForRegion(region);
	}

	@GetMapping("/regions")
	@Operation(summary = "List all TMDB-supported region codes",
		description = "Returns every region code TMDB supports for watch-provider lookups, "
			+ "sorted by region name. Results are cached.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Regions returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API request failed")
	})
	public List<WatchRegionResponse> getRegions() {
		return watchProviderService.regions();
	}
}
