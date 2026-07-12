package com.wewatch.api.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.User;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;

// Watch-provider awareness (#270): resolves which streaming services a shelf set
// may assume, and annotates the titles it serves with the ones that carry them.
class ProviderContextResolver {

	private final UserRepository userRepository;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;

	ProviderContextResolver(UserRepository userRepository, TmdbTitleCacheRepository tmdbTitleCacheRepository) {
		this.userRepository = userRepository;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
	}

	// Union across members with a setting bearing on it; disabled entirely when
	// regions conflict or nobody has configured services — behavior is then
	// exactly the pre-#270 one.
	ProviderContext resolve(List<Long> memberUserIds) {
		List<User> configured = userRepository.findAllById(memberUserIds).stream()
			.filter(u -> u.getWatchRegion() != null
				&& u.getWatchProviderIds() != null && !u.getWatchProviderIds().isEmpty())
			.toList();
		if (configured.isEmpty()) return ProviderContext.DISABLED;

		Set<String> regions = configured.stream().map(User::getWatchRegion).collect(Collectors.toSet());
		if (regions.size() > 1) return ProviderContext.DISABLED;

		Set<Integer> union = configured.stream()
			.flatMap(u -> u.getWatchProviderIds().stream())
			.collect(Collectors.toSet());
		return new ProviderContext(regions.iterator().next(), union);
	}

	// Rewrites served titles with the intersection of their cached flatrate
	// providers (context region) and the members' services (#270). One batch
	// cache read for all shelves. Coverage is partial by design — candidates
	// without a tmdb_title_cache row keep a null providerIds ("unknown"), the
	// same tradeoff as the keyword/person boosts; the title detail page fills
	// the gap with live data.
	List<SuggestionShelfResponse> attachBadges(List<SuggestionShelfResponse> shelves, ProviderContext ctx) {
		if (!ctx.enabled() || shelves.isEmpty()) return shelves;

		List<String> ids = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.distinct()
			.toList();
		Map<String, List<Integer>> badgesById = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			List<Integer> mine = ctx.streamableOn(cached);
			if (!mine.isEmpty()) badgesById.put(cached.getTmdbId(), mine);
		}
		if (badgesById.isEmpty()) return shelves;

		return shelves.stream()
			.map(s -> new SuggestionShelfResponse(
				s.reason(),
				s.titles().stream()
					.map(t -> badgesById.containsKey(t.externalId())
						? new TitleSearchResponse(t.externalId(), t.externalSource(), t.type(), t.name(),
							t.overview(), t.releaseDate(), t.posterUrl(), t.genreIds(), badgesById.get(t.externalId()))
						: t)
					.toList(),
				s.kind(),
				s.providerFiltered()))
			.toList();
	}
}
