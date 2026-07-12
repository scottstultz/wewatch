package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbTitleCacheRepository;

// Ranks raw TMDB candidates against the taste profile. Draws from the shared
// day-seeded rng (once per distinct candidate), so callers must invoke it in a
// fixed order — see SuggestionContext.
class CandidateScorer {

	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;
	private final SuggestionTuningProperties tuning;

	CandidateScorer(TmdbTitleCacheRepository tmdbTitleCacheRepository, SuggestionTuningProperties tuning) {
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
		this.tuning = tuning;
	}

	// Dedupe by externalId, then sort by taste-profile score: genre affinity plus
	// keyword boost plus day-seeded score jitter (#248) — a score-proportional ±
	// offset per candidate (#267) that rotates the ranking of near-peers daily
	// beyond exact ties, so stable high-affinity candidates don't float to the top
	// of a shelf every single day. Applied even with no genre/keyword signal, where
	// the floor amplitude degrades the sort to a stable-per-day random order. Also
	// ranks the pooled catch-all shelf (#266).
	List<TitleSearchResponse> rankByTasteProfile(List<TitleSearchResponse> results, SuggestionContext ctx) {
		Set<String> seen = new HashSet<>();
		List<TitleSearchResponse> deduped = results.stream()
			.filter(r -> seen.add(r.externalId()))
			.collect(Collectors.toCollection(ArrayList::new));

		Map<String, Double> cacheBoosts = cacheSignalBoosts(deduped, ctx);
		ToDoubleFunction<TitleSearchResponse> baseScore = r ->
			genreScore(r, ctx.profile().genreProfile()) + cacheBoosts.getOrDefault(r.externalId(), 0.0);
		Map<String, Double> jitter = jitterByCandidate(deduped, baseScore, ctx.rng());

		deduped.sort(Comparator.comparingDouble((TitleSearchResponse r) ->
			baseScore.applyAsDouble(r)
			+ jitter.getOrDefault(r.externalId(), 0.0)).reversed());
		return deduped;
	}

	double genreScore(TitleSearchResponse candidate, Map<Integer, Double> genreProfile) {
		List<Integer> genres = candidate.genreIds();
		if (genres == null || genres.isEmpty()) return 0.0;
		return genres.stream().mapToDouble(id -> genreProfile.getOrDefault(id, 0.0)).sum();
	}

	// Day-seeded per-candidate score offset (#248). Draws from the shared day-seeded
	// rng in list order, so the assignment is reproducible within a day (identical
	// shelves across recomputes) and rotates at midnight. Keyed by externalId, one
	// draw per distinct id so duplicates don't desync the rng stream. The amplitude
	// is proportional to the candidate's base score with an absolute floor (#267);
	// scaling happens after the draw, so amplitude never affects rng consumption.
	Map<String, Double> jitterByCandidate(
		List<TitleSearchResponse> candidates,
		ToDoubleFunction<TitleSearchResponse> baseScore,
		Random rng
	) {
		Map<String, Double> jitter = new HashMap<>();
		for (TitleSearchResponse c : candidates) {
			jitter.computeIfAbsent(c.externalId(), id -> {
				double amplitude = Math.max(tuning.getScoreJitterFloor(),
					tuning.getScoreJitterFraction() * baseScore.applyAsDouble(c));
				return (rng.nextDouble() * 2 - 1) * amplitude;
			});
		}
		return jitter;
	}

	// TMDB recommendations/similar responses carry no keywords, credits, or
	// provider data, so the keyword (#232), person (#269), and streamability
	// (#270) boosts all come from tmdb_title_cache in one batch read: candidates
	// without a cache row get none — a partial signal, but one DB read instead
	// of a TMDB call per candidate
	private Map<String, Double> cacheSignalBoosts(List<TitleSearchResponse> candidates, SuggestionContext ctx) {
		Set<Integer> keywordProfile = ctx.profile().keywordIds();
		Set<Integer> personProfile = ctx.profile().personIds();
		ProviderContext providerCtx = ctx.providers();
		if ((keywordProfile.isEmpty() && personProfile.isEmpty() && !providerCtx.enabled()) || candidates.isEmpty()) {
			return Map.of();
		}
		List<String> ids = candidates.stream().map(TitleSearchResponse::externalId).toList();
		Map<String, Double> boosts = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			double boost = 0.0;
			if (cached.getKeywordIds() != null) {
				boost += tuning.getKeywordMatchWeight()
					* cached.getKeywordIds().stream().filter(keywordProfile::contains).count();
			}
			// Set-union of cast and director ids: acting in and directing the same
			// title is one shared person, not two
			boost += tuning.getPersonMatchWeight()
				* cachedPersonIds(cached).stream().filter(personProfile::contains).count();
			// Flat, not per-service (#270): being on two of the user's services
			// doesn't make a title more watchable than being on one
			if (providerCtx.enabled() && !providerCtx.streamableOn(cached).isEmpty()) {
				boost += tuning.getStreamableBoost();
			}
			if (boost > 0) boosts.put(cached.getTmdbId(), boost);
		}
		return boosts;
	}

	private Set<Integer> cachedPersonIds(TmdbTitleCache cached) {
		Set<Integer> ids = new HashSet<>();
		if (cached.getTopCast() != null) cached.getTopCast().forEach(p -> ids.add(p.id()));
		if (cached.getDirectors() != null) cached.getDirectors().forEach(p -> ids.add(p.id()));
		return ids;
	}
}
