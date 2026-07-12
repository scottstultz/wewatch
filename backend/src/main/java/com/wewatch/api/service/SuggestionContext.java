package com.wewatch.api.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchlistEntry;

// Everything one compute pass shares. The stages used to pass these around as
// six- and seven-argument chains; bundling them keeps the shelf builders honest
// about the fact that they all read the same inputs and write the same dedup set.
//
// Two of the fields are deliberately mutable state, not values:
//
//   seen — the cross-shelf dedup set, seeded with owned titles and dismissals
//          (#268) and added to by every shelf as it fills. A title appears at
//          most once across the whole shelf set because every stage funnels
//          through this one set, so builders run in a meaningful order: earlier
//          builders get first pick.
//
//   rng  — the day-seeded Random (#231) that drives every rotation decision.
//          It is a single shared stream, so the *order* stages draw from it is
//          part of the behavior: reordering the stages, or adding/removing a
//          draw, changes what every user sees. Stages that bail out before
//          drawing (an empty person profile, no named keywords) must keep
//          bailing out before drawing.
record SuggestionContext(
	LocalDate today,
	// Every member of the list. Impressions (#264), dismissals (#268), ratings
	// (#273), and the provider union (#270) are all scoped to these users rather
	// than to the list, so a shared shelf answers to everyone on it.
	List<Long> memberUserIds,
	List<WatchlistEntry> entries,
	Map<Long, Title> titlesById,
	Map<String, TmdbTitleCache> cacheByTmdbId,
	TasteProfile profile,
	ProviderContext providers,
	// The services every member has, where `providers` above is the union of them
	// (#322). Disabled unless the list has 2+ members who have all configured a
	// region and providers and share at least one service — see
	// ProviderContextResolver.resolveShared.
	ProviderContext sharedProviders,
	Set<String> seen,
	Map<String, Double> recencyWeights,
	Random rng
) {

	// The cached TMDB row for an entry's title, or null when the title has no
	// external id or hasn't been cached yet
	TmdbTitleCache cachedRowFor(WatchlistEntry entry) {
		Title title = titlesById.get(entry.getTitleId());
		if (title == null || title.getExternalId() == null) return null;
		return cacheByTmdbId.get(title.getExternalId());
	}
}
