package com.wewatch.api.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.repository.SuggestionImpressionRepository;
import com.wewatch.api.repository.projection.SuggestionLastShown;

@Service
public class SuggestionImpressionService {

	private final SuggestionImpressionRepository repository;
	private final Clock clock;
	// Now the recency-penalty window (#264): how many days a shown title keeps
	// sinking in the ranking before it fully recovers. Property name kept from the
	// binary-suppression era it replaces (#233).
	private final int suppressionDays;

	public SuggestionImpressionService(
		SuggestionImpressionRepository repository,
		Clock clock,
		@Value("${suggestions.impressions.suppression-days}") int suppressionDays
	) {
		this.repository = repository;
		this.clock = clock;
		this.suppressionDays = suppressionDays;
	}

	// Recency weight per title shown on a *previous* day within the window (#264),
	// across every user whose impressions the shelf answers to (#247 — a watchlist's
	// members): 1.0 for shown-yesterday, decaying linearly to 1/window at the window
	// edge; titles older than the window are absent (rank fully recovered). Today's
	// own impressions are excluded so same-day recomputes see identical inputs and
	// reproduce identical shelves (#231's day-seeded rotation).
	public Map<String, Double> recencyWeights(Collection<Long> userIds) {
		if (userIds.isEmpty()) return Map.of();
		LocalDate today = LocalDate.now(clock);
		Map<String, Double> weights = new HashMap<>();
		for (SuggestionLastShown shown : repository.findLastShown(userIds, today.minusDays(suppressionDays), today)) {
			long daysSince = ChronoUnit.DAYS.between(shown.getShownOn(), today);
			weights.put(shown.getTmdbId(), (suppressionDays + 1 - daysSince) / (double) suppressionDays);
		}
		return weights;
	}

	@Transactional
	public void recordShown(Collection<Long> userIds, Collection<String> tmdbIds) {
		if (userIds.isEmpty() || tmdbIds.isEmpty()) return;
		LocalDate today = LocalDate.now(clock);
		// Record against every member so the penalty follows each of them into their
		// other lists (#247), one row per (user, title, day): re-serving a title is
		// safe to re-record under a continuous penalty — it just starts sinking again,
		// unlike the binary window where a re-recorded top-up never aged out (#246).
		for (Long userId : userIds) {
			for (String tmdbId : tmdbIds) {
				repository.insertImpression(userId, tmdbId, today);
			}
		}
		// Rows past the window carry no penalty — prune them while we already hold
		// a write transaction for these users
		repository.deleteShownBefore(userIds, today.minusDays(suppressionDays));
	}
}
