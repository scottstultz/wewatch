package com.wewatch.api.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.tmdb.TmdbClient;

@Service
public class SuggestionService {

	private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
	private static final int MAX_SEEDS = 6;
	private static final int MIN_RESULTS = 5;
	private static final int MAX_RESULTS = 20;
	private static final long CACHE_TTL_SECONDS = 30 * 60;

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final TitleService titleService;
	private final TmdbClient tmdbClient;

	private record CachedShelf(List<TitleSearchResponse> titles, Instant fetchedAt) {}
	private final Map<Long, CachedShelf> cache = new ConcurrentHashMap<>();

	public SuggestionService(
		WatchlistEntryRepository watchlistEntryRepository,
		TitleService titleService,
		TmdbClient tmdbClient
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
	}

	public List<TitleSearchResponse> topPicks(Long watchlistId) {
		CachedShelf cached = cache.get(watchlistId);
		if (cached != null && cached.fetchedAt().isAfter(Instant.now().minusSeconds(CACHE_TTL_SECONDS))) {
			return cached.titles();
		}
		List<TitleSearchResponse> titles = compute(watchlistId);
		cache.put(watchlistId, new CachedShelf(titles, Instant.now()));
		return titles;
	}

	public void invalidate(Long watchlistId) {
		cache.remove(watchlistId);
	}

	private List<TitleSearchResponse> compute(Long watchlistId) {
		List<WatchlistEntry> allEntries = watchlistEntryRepository
			.findByWatchlistId(watchlistId, null, Pageable.unpaged())
			.getContent();

		Set<String> ownedExternalIds = allEntries.stream()
			.map(WatchlistEntry::getExternalId)
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(HashSet::new));

		List<Long> titleIds = allEntries.stream().map(WatchlistEntry::getTitleId).toList();
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);

		// Seeds: WATCHING first, then WANT_TO_WATCH; most-recently-updated first
		List<WatchlistEntry> seeds = allEntries.stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHING || e.getStatus() == WatchStatus.WANT_TO_WATCH)
			.filter(e -> titlesById.containsKey(e.getTitleId()))
			.sorted(Comparator.comparingInt((WatchlistEntry e) -> statusWeight(e.getStatus()))
				.reversed()
				.thenComparing(WatchlistEntry::getUpdatedAt, Comparator.reverseOrder()))
			.limit(MAX_SEEDS)
			.toList();

		// Fan-out recommendations per seed
		Map<String, Integer> frequency = new HashMap<>();
		Map<String, TitleSearchResponse> candidates = new HashMap<>();

		for (WatchlistEntry seed : seeds) {
			Title title = titlesById.get(seed.getTitleId());
			if (title == null || title.getExternalId() == null) continue;
			try {
				for (TitleSearchResponse rec : tmdbClient.getRecommendations(title.getType(), title.getExternalId())) {
					if (ownedExternalIds.contains(rec.externalId())) continue;
					frequency.merge(rec.externalId(), 1, Integer::sum);
					candidates.putIfAbsent(rec.externalId(), rec);
				}
			} catch (TmdbApiException e) {
				log.warn("Recommendations failed for seed {}: {}", title.getExternalId(), e.getMessage());
			}
		}

		// Sort by frequency desc, cap
		List<TitleSearchResponse> results = frequency.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(MAX_RESULTS)
			.map(e -> candidates.get(e.getKey()))
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(ArrayList::new));

		// Trending TV fallback if too few results
		if (results.size() < MIN_RESULTS) {
			try {
				Set<String> present = results.stream()
					.map(TitleSearchResponse::externalId)
					.collect(Collectors.toCollection(HashSet::new));
				for (TitleSearchResponse t : tmdbClient.getTrending(TitleType.TV)) {
					if (!ownedExternalIds.contains(t.externalId()) && present.add(t.externalId())) {
						results.add(t);
						if (results.size() >= MAX_RESULTS) break;
					}
				}
			} catch (TmdbApiException e) {
				log.warn("Trending TV fallback failed: {}", e.getMessage());
			}
		}

		return results;
	}

	private int statusWeight(WatchStatus status) {
		return switch (status) {
			case WATCHING -> 2;
			case WANT_TO_WATCH -> 1;
			case WATCHED -> 0;
		};
	}
}
