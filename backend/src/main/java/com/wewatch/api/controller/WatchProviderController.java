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

// Read-only streaming-service catalog (#270): the settings picker loads
// regions and the per-region provider list from here; Discover uses the same
// provider list to resolve badge ids to names/logos.
@RestController
@Validated
@RequestMapping("/api/watch-providers")
public class WatchProviderController {

	private final WatchProviderService watchProviderService;

	public WatchProviderController(WatchProviderService watchProviderService) {
		this.watchProviderService = watchProviderService;
	}

	@GetMapping
	public List<WatchProviderResponse> getProviders(
		@RequestParam @Pattern(regexp = "[A-Za-z]{2}") String region
	) {
		return watchProviderService.providersForRegion(region);
	}

	@GetMapping("/regions")
	public List<WatchRegionResponse> getRegions() {
		return watchProviderService.regions();
	}
}
