package com.wewatch.api.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.dto.WatchRegionResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbWatchProvider;
import com.wewatch.api.tmdb.TmdbWatchRegion;

// Region-scoped streaming-service catalog (#270). The TMDB provider and region
// lists change on the order of months, so they're cached in-process with a long
// TTL — two TMDB calls per region per TTL window instead of two per settings-
// page visit.
@Service
public class WatchProviderService {

	private static final String REGIONS_KEY = "regions";

	private final TmdbClient tmdbClient;
	private final Cache<String, List<WatchProviderResponse>> providerCache;
	private final Cache<String, List<WatchRegionResponse>> regionCache;

	public WatchProviderService(
		TmdbClient tmdbClient,
		@Value("${tmdb.providers.ttl-hours:24}") long ttlHours
	) {
		this.tmdbClient = tmdbClient;
		this.providerCache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofHours(ttlHours))
			.maximumSize(100)
			.build();
		this.regionCache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofHours(ttlHours))
			.maximumSize(1)
			.build();
	}

	// Users subscribe to a service, not to "Netflix for movies" — merge the
	// movie and TV catalogs into one picker list, keeping the better (lower)
	// display priority when a provider appears in both.
	public List<WatchProviderResponse> providersForRegion(String region) {
		return providerCache.get(region.toUpperCase(), r -> {
			Map<Integer, WatchProviderResponse> byId = new LinkedHashMap<>();
			for (TitleType type : List.of(TitleType.MOVIE, TitleType.TV)) {
				for (TmdbWatchProvider p : tmdbClient.getWatchProviders(type, r)) {
					WatchProviderResponse candidate = new WatchProviderResponse(
						p.providerId(),
						p.providerName(),
						TmdbClient.providerLogoUrl(p.logoPath()),
						p.displayPriority() != null ? p.displayPriority() : Integer.MAX_VALUE);
					byId.merge(p.providerId(), candidate,
						(a, b) -> a.displayPriority() <= b.displayPriority() ? a : b);
				}
			}
			return byId.values().stream()
				.sorted(Comparator.comparingInt(WatchProviderResponse::displayPriority)
					.thenComparing(WatchProviderResponse::name))
				.toList();
		});
	}

	public List<WatchRegionResponse> regions() {
		return regionCache.get(REGIONS_KEY, k -> tmdbClient.getWatchRegions().stream()
			.map(r -> new WatchRegionResponse(r.code(), r.englishName()))
			.sorted(Comparator.comparing(WatchRegionResponse::name))
			.toList());
	}
}
