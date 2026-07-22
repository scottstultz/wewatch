package com.wewatch.api.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

// Title-anchored "More Like This" row (#358). Answers "what else is like the
// title I'm looking at?" straight from TMDB's own recommendations, distinct from
// the personalized Discover shelves (which also lean on getRecommendations via
// SeedShelfBuilder but blend a taste profile). The detail page fetches this lazily
// — only when the user opens the tab — so this is cached in-process to spare a
// repeat TMDB call when the same popular title is viewed again, by anyone.
@Service
public class RecommendationService {

	// Mirror SeedShelfBuilder: top up with `similar` only when recommendations
	// come back thin, so sparse titles still fill the row.
	private static final int SIMILAR_TOP_UP_THRESHOLD = 5;
	// Bound the row — one screen of tiles is plenty for an anchored suggestion.
	private static final int MAX_RESULTS = 20;

	private final TmdbClient tmdbClient;
	private final Cache<String, List<TitleSearchResponse>> cache;

	public RecommendationService(
		TmdbClient tmdbClient,
		@Value("${tmdb.recommendations.ttl-hours:12}") long ttlHours
	) {
		this.tmdbClient = tmdbClient;
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofHours(ttlHours))
			.maximumSize(200)
			.build();
	}

	public List<TitleSearchResponse> recommendationsFor(TitleType type, String externalId) {
		// A TmdbApiException from the loader propagates (→ 502 via ApiExceptionHandler)
		// and Caffeine stores nothing, so a TMDB outage isn't poisoned into the cache.
		return cache.get(type + ":" + externalId, k -> fetchWithFallback(type, externalId));
	}

	// Page 1 only, deterministic so the cache key stays bounded. Recommendations
	// first; when they're thin, append `similar`. Dedup across the two feeds by
	// external id, drop the anchor title if TMDB echoes it back, cap the row.
	private List<TitleSearchResponse> fetchWithFallback(TitleType type, String externalId) {
		List<TitleSearchResponse> results = new ArrayList<>(tmdbClient.getRecommendations(type, externalId, 1));
		if (results.size() < SIMILAR_TOP_UP_THRESHOLD) {
			results.addAll(tmdbClient.getSimilar(type, externalId, 1));
		}

		Map<String, TitleSearchResponse> byId = new LinkedHashMap<>();
		for (TitleSearchResponse candidate : results) {
			if (candidate.externalId().equals(externalId)) {
				continue; // never recommend the title back to itself
			}
			byId.putIfAbsent(candidate.externalId(), candidate);
		}
		return byId.values().stream().limit(MAX_RESULTS).toList();
	}
}
