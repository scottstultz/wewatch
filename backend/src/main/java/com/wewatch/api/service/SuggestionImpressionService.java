package com.wewatch.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.repository.SuggestionImpressionRepository;

@Service
public class SuggestionImpressionService {

	private final SuggestionImpressionRepository repository;
	private final Clock clock;
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

	// Titles shown within the suppression window on a *previous* day. Today's own
	// impressions are excluded so same-day recomputes reproduce identical shelves
	// (#231's day-seeded rotation); a title shown this morning only becomes
	// suppressed at the next day rollover.
	public Set<String> recentlyShownIds(Long watchlistId) {
		return Set.copyOf(repository.findShownTmdbIds(watchlistId, windowStart(), startOfToday()));
	}

	@Transactional
	public void recordShown(Long watchlistId, Collection<String> tmdbIds) {
		if (tmdbIds.isEmpty()) return;
		Instant now = clock.instant();
		for (String tmdbId : tmdbIds) {
			repository.upsertImpression(watchlistId, tmdbId, now);
		}
		// Rows that aged out of the window are dead weight — prune them while we
		// already hold a write transaction for this watchlist
		repository.deleteShownBefore(watchlistId, windowStart());
	}

	private Instant startOfToday() {
		return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
	}

	private Instant windowStart() {
		return LocalDate.now(clock).minusDays(suppressionDays).atStartOfDay(clock.getZone()).toInstant();
	}
}
