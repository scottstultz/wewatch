package com.wewatch.api.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wewatch.api.model.TmdbTitleCache;

// The provider context a shelf set answers to (#270): the union of member
// services, valid only when every configured member agrees on a region.
// Availability is region-scoped, and TMDB's discover filter takes exactly
// one watch_region — with members in different regions there is no single
// truthful answer, so provider-awareness turns off for that list (documented
// shared-list simplification; per-member shelves would be the real fix).
record ProviderContext(String region, Set<Integer> providerIds) {

	static final ProviderContext DISABLED = new ProviderContext(null, Set.of());

	boolean enabled() {
		return region != null && !providerIds.isEmpty();
	}

	// Discover wants a list (ordered, joinable); null when disabled so the
	// TMDB client skips the filter entirely
	List<Integer> providerIdList() {
		return enabled() ? List.copyOf(providerIds) : null;
	}

	// The members' services carrying this cached title in the context region;
	// empty when unknown (no cache row data) or streamable nowhere relevant
	List<Integer> streamableOn(TmdbTitleCache cached) {
		Map<String, List<Integer>> providers = cached.getWatchProviders();
		List<Integer> regionIds = providers != null ? providers.get(region) : null;
		if (regionIds == null) return List.of();
		return regionIds.stream().filter(providerIds::contains).toList();
	}
}
