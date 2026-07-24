package com.wewatch.api.service;

import java.util.HashMap;
import java.util.HashSet;
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

	// The two provider contexts a shelf set answers to, from one read of the members.
	// They ask different questions of the same settings, so they're resolved together
	// rather than each fetching the members again.
	record ProviderContexts(ProviderContext union, ProviderContext shared) {}

	ProviderContexts resolve(List<Long> memberUserIds) {
		List<User> members = userRepository.findAllById(memberUserIds);
		return new ProviderContexts(union(members), shared(memberUserIds, members));
	}

	// "Someone here can stream it" (#270). Ignores members with nothing configured —
	// they add nothing to a union — and disables entirely when regions conflict or
	// nobody has configured services, which is exactly the pre-#270 behavior.
	private ProviderContext union(List<User> members) {
		List<User> configured = members.stream()
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

	// "We can *all* stream it" (#322) — what the both-watch shelf answers to.
	//
	// Strict, where the union is permissive. Skipping an unconfigured member is the
	// right call for a union and the wrong one for an intersection: it would have
	// them silently agreeing to a service they may not have. So an unconfigured
	// member, a region conflict, a single-member list, or an empty intersection all
	// disable the shelf outright — its whole promise is that everyone has the
	// service, and a partial answer to that is a wrong answer, not a smaller one.
	private ProviderContext shared(List<Long> memberUserIds, List<User> members) {
		if (memberUserIds.size() < 2 || members.size() != memberUserIds.size()) {
			return ProviderContext.DISABLED;
		}

		Set<String> regions = new HashSet<>();
		Set<Integer> shared = null;
		for (User member : members) {
			List<Integer> providerIds = member.getWatchProviderIds();
			if (member.getWatchRegion() == null || providerIds == null || providerIds.isEmpty()) {
				return ProviderContext.DISABLED;
			}
			regions.add(member.getWatchRegion());
			if (shared == null) {
				shared = new HashSet<>(providerIds);
			} else {
				shared.retainAll(providerIds);
			}
		}
		if (regions.size() > 1 || shared.isEmpty()) return ProviderContext.DISABLED;

		return new ProviderContext(regions.iterator().next(), shared);
	}

	// Rewrites served titles with the intersection of their cached flatrate
	// providers (context region) and the members' services (#270). One batch
	// cache read for all shelves. Coverage is partial by design — candidates
	// without a tmdb_title_cache row keep a null providerIds ("unknown"), the
	// same tradeoff as the keyword/person boosts; the title detail page fills
	// the gap with live data.
	//
	// Two contexts, because two shelves can make different promises about the same
	// title (#322): a BOTH_WATCH tile badged with a service only one member
	// subscribes to would contradict its own shelf label, so that shelf badges
	// from the intersection while everything else badges from the union.
	List<SuggestionShelfResponse> attachBadges(
		List<SuggestionShelfResponse> shelves,
		ProviderContext union,
		ProviderContext shared
	) {
		if (shelves.isEmpty() || (!union.enabled() && !shared.enabled())) return shelves;

		List<String> ids = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.distinct()
			.toList();
		Map<String, TmdbTitleCache> cachedById = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			cachedById.put(cached.getTmdbId(), cached);
		}
		if (cachedById.isEmpty()) return shelves;

		return shelves.stream()
			.map(s -> badgeShelf(s, cachedById,
				s.kind() == SuggestionShelfResponse.ShelfKind.BOTH_WATCH ? shared : union))
			.toList();
	}

	private SuggestionShelfResponse badgeShelf(
		SuggestionShelfResponse shelf,
		Map<String, TmdbTitleCache> cachedById,
		ProviderContext ctx
	) {
		if (!ctx.enabled()) return shelf;
		return new SuggestionShelfResponse(
			shelf.reason(),
			shelf.titles().stream()
				.map(t -> {
					TmdbTitleCache cached = cachedById.get(t.externalId());
					List<Integer> mine = cached != null ? ctx.streamableOn(cached) : List.of();
					return mine.isEmpty() ? t
						// Canonical 10-arg form on purpose: the shorter overloads null
						// popularity, and this rebuild sits on exactly the shelves the
						// suggestion pipeline scores (#374).
						: new TitleSearchResponse(t.externalId(), t.externalSource(), t.type(), t.name(),
							t.overview(), t.releaseDate(), t.posterUrl(), t.genreIds(), mine,
							t.popularity());
				})
				.toList(),
			shelf.kind(),
			shelf.providerFiltered());
	}
}
