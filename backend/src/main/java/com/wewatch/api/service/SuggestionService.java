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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.tmdb.TmdbClient;

@Service
public class SuggestionService {

	private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
	private static final int MAX_SEEDS = 3;
	private static final int GENRE_GROUP_THRESHOLD = 3;
	private static final int MAX_GENRES = 3;
	private static final int MAX_KEYWORDS = 5;
	private static final int MIN_SHELF_SIZE = 3;
	private static final int MAX_SHELF_SIZE = 12;
	private static final int MAX_PER_GENRE_CLUSTER = 4;
	private static final int SIMILAR_TOP_UP_THRESHOLD = 5;
	private static final long CACHE_TTL_SECONDS = 30 * 60;
	private static final int DISCOVER_VOTE_COUNT_GTE = 100;

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final TitleService titleService;
	private final TmdbClient tmdbClient;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;

	private record CachedShelf(List<SuggestionShelfResponse> shelves, Instant fetchedAt) {}
	private final Map<Long, CachedShelf> cache = new ConcurrentHashMap<>();

	public SuggestionService(
		WatchlistEntryRepository watchlistEntryRepository,
		TitleService titleService,
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository tmdbTitleCacheRepository
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
	}

	public List<SuggestionShelfResponse> topPicks(Long watchlistId) {
		CachedShelf cached = cache.get(watchlistId);
		if (cached != null && cached.fetchedAt().isAfter(Instant.now().minusSeconds(CACHE_TTL_SECONDS))) {
			return cached.shelves();
		}
		List<SuggestionShelfResponse> shelves = compute(watchlistId);
		cache.put(watchlistId, new CachedShelf(shelves, Instant.now()));
		return shelves;
	}

	@Async
	public void recompute(Long watchlistId) {
		cache.remove(watchlistId);
		List<SuggestionShelfResponse> shelves = compute(watchlistId);
		cache.put(watchlistId, new CachedShelf(shelves, Instant.now()));
	}

	private List<SuggestionShelfResponse> compute(Long watchlistId) {
		List<WatchlistEntry> allEntries = watchlistEntryRepository
			.findByWatchlistId(watchlistId, null, Pageable.unpaged())
			.getContent();

		if (allEntries.isEmpty()) return List.of();

		List<Long> titleIds = allEntries.stream().map(WatchlistEntry::getTitleId).toList();
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);

		Set<String> ownedExternalIds = allEntries.stream()
			.map(WatchlistEntry::getExternalId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		// Load cache entries once for genre + keyword profile building
		Map<String, TmdbTitleCache> cacheByTmdbId = tmdbTitleCacheRepository.findAllById(ownedExternalIds)
			.stream().collect(Collectors.toMap(TmdbTitleCache::getTmdbId, c -> c));

		Map<Integer, Double> genreProfile = buildGenreProfile(allEntries, titlesById, cacheByTmdbId);
		List<Integer> keywordProfile = buildKeywordProfile(cacheByTmdbId.values());

		// Cross-shelf dedup: start from all owned externalIds
		Set<String> seen = new HashSet<>(ownedExternalIds);

		List<SuggestionShelfResponse> shelves = new ArrayList<>();

		// ── Per-seed shelves ───────────────────────────────────────
		List<WatchlistEntry> seeds = allEntries.stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHING || e.getStatus() == WatchStatus.WANT_TO_WATCH)
			.filter(e -> titlesById.containsKey(e.getTitleId()))
			.sorted(Comparator.comparingInt((WatchlistEntry e) -> statusWeight(e.getStatus()))
				.reversed()
				.thenComparing(WatchlistEntry::getUpdatedAt, Comparator.reverseOrder()))
			.limit(MAX_SEEDS)
			.toList();

		for (WatchlistEntry seed : seeds) {
			Title title = titlesById.get(seed.getTitleId());
			if (title == null || title.getExternalId() == null) continue;

			List<TitleSearchResponse> candidates = fetchScoredCandidates(title.getType(), title.getExternalId(), genreProfile);
			List<TitleSearchResponse> shelf = fillShelf(candidates, seen);

			if (shelf.size() >= MIN_SHELF_SIZE) {
				String label = title.getName() != null
					? "Because you added " + title.getName()
					: "Because of your list";
				shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.PER_SEED));
			}
		}

		// ── Genre-profile shelves (conditional, per media type) ───
		for (TitleType type : List.of(TitleType.TV, TitleType.MOVIE)) {
			List<WatchlistEntry> group = allEntries.stream()
				.filter(e -> {
					Title t = titlesById.get(e.getTitleId());
					return t != null && t.getType() == type;
				})
				.toList();

			if (group.size() < GENRE_GROUP_THRESHOLD) continue;

			Map<Integer, Integer> genreFreq = new HashMap<>();
			for (WatchlistEntry e : group) {
				Title t = titlesById.get(e.getTitleId());
				if (t == null || t.getExternalId() == null) continue;
				TmdbTitleCache cached = cacheByTmdbId.get(t.getExternalId());
				if (cached == null || cached.getGenreIds() == null) continue;
				for (int genreId : cached.getGenreIds()) {
					genreFreq.merge(genreId, 1, Integer::sum);
				}
			}

			if (genreFreq.isEmpty()) continue;

			List<Integer> topGenres = genreFreq.entrySet().stream()
				.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
				.limit(MAX_GENRES)
				.map(Map.Entry::getKey)
				.toList();

			// Type-scoped keywords to avoid cross-namespace contamination
			List<Integer> typeKeywords = group.stream()
				.map(e -> titlesById.get(e.getTitleId()))
				.filter(Objects::nonNull)
				.map(t -> cacheByTmdbId.get(t.getExternalId()))
				.filter(Objects::nonNull)
				.flatMap(c -> c.getKeywordIds() != null ? c.getKeywordIds().stream() : java.util.stream.Stream.empty())
				.collect(Collectors.groupingBy(k -> k, Collectors.summingInt(k -> 1)))
				.entrySet().stream()
				.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
				.limit(MAX_KEYWORDS)
				.map(Map.Entry::getKey)
				.toList();

			String label = type == TitleType.TV ? "More like your shows" : "More like your movies";
			try {
				List<TitleSearchResponse> discovered = tmdbClient.discover(type, topGenres, typeKeywords, DISCOVER_VOTE_COUNT_GTE);
				List<TitleSearchResponse> shelf = fillShelf(discovered, seen);
				if (shelf.size() >= MIN_SHELF_SIZE) {
					shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.GENRE_PROFILE));
				}
			} catch (TmdbApiException e) {
				log.warn("Discover failed for {} genres {}: {}", type, topGenres, e.getMessage());
			}
		}

		return shelves;
	}

	// Fetch candidates from recommendations + similar top-up, then score by genre affinity
	private List<TitleSearchResponse> fetchScoredCandidates(TitleType type, String tmdbId, Map<Integer, Double> genreProfile) {
		List<TitleSearchResponse> results = new ArrayList<>();
		try {
			results.addAll(tmdbClient.getRecommendations(type, tmdbId));
		} catch (TmdbApiException e) {
			log.warn("Recommendations failed for {}: {}", tmdbId, e.getMessage());
		}
		if (results.size() < SIMILAR_TOP_UP_THRESHOLD) {
			try {
				results.addAll(tmdbClient.getSimilar(type, tmdbId));
			} catch (TmdbApiException e) {
				log.warn("Similar failed for {}: {}", tmdbId, e.getMessage());
			}
		}
		// Deduplicate within this fetch before scoring
		Set<String> seen = new HashSet<>();
		List<TitleSearchResponse> deduped = results.stream()
			.filter(r -> seen.add(r.externalId()))
			.toList();

		if (genreProfile.isEmpty()) return deduped;

		return deduped.stream()
			.sorted(Comparator.comparingDouble((TitleSearchResponse r) -> genreScore(r, genreProfile)).reversed())
			.toList();
	}

	// Fill a shelf with diversification: cap same-primary-genre candidates to MAX_PER_GENRE_CLUSTER
	private List<TitleSearchResponse> fillShelf(List<TitleSearchResponse> candidates, Set<String> seen) {
		Map<Integer, Integer> genreCount = new HashMap<>();
		List<TitleSearchResponse> shelf = new ArrayList<>();
		for (TitleSearchResponse r : candidates) {
			if (!seen.add(r.externalId())) continue;
			List<Integer> genres = r.genreIds() != null ? r.genreIds() : List.of();
			int primaryGenre = genres.isEmpty() ? -1 : genres.get(0);
			if (primaryGenre != -1 && genreCount.getOrDefault(primaryGenre, 0) >= MAX_PER_GENRE_CLUSTER) continue;
			shelf.add(r);
			if (primaryGenre != -1) genreCount.merge(primaryGenre, 1, Integer::sum);
			if (shelf.size() >= MAX_SHELF_SIZE) break;
		}
		return shelf;
	}

	private double genreScore(TitleSearchResponse candidate, Map<Integer, Double> genreProfile) {
		List<Integer> genres = candidate.genreIds();
		if (genres == null || genres.isEmpty()) return 0.0;
		return genres.stream().mapToDouble(id -> genreProfile.getOrDefault(id, 0.0)).sum();
	}

	private Map<Integer, Double> buildGenreProfile(
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId
	) {
		Map<Integer, Double> profile = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(t.getExternalId());
			if (cached == null || cached.getGenreIds() == null) continue;
			double weight = statusWeight(e.getStatus());
			for (int genreId : cached.getGenreIds()) {
				profile.merge(genreId, weight, Double::sum);
			}
		}
		return profile;
	}

	private List<Integer> buildKeywordProfile(java.util.Collection<TmdbTitleCache> caches) {
		Map<Integer, Integer> freq = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			if (c.getKeywordIds() == null) continue;
			for (int kw : c.getKeywordIds()) freq.merge(kw, 1, Integer::sum);
		}
		return freq.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(MAX_KEYWORDS)
			.map(Map.Entry::getKey)
			.toList();
	}

	private int statusWeight(WatchStatus status) {
		return switch (status) {
			case WATCHING -> 2;
			case WANT_TO_WATCH -> 1;
			case WATCHED -> 0;
		};
	}
}
