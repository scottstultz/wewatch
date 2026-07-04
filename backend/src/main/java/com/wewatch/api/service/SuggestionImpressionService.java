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

	// Titles shown within the suppression window on a *previous* day, across every
	// user whose suppression the shelf answers to (#247 — a watchlist's members).
	// Today's own impressions are excluded so same-day recomputes reproduce identical
	// shelves (#231's day-seeded rotation); a title shown this morning only becomes
	// suppressed at the next day rollover.
	public Set<String> recentlyShownIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) return Set.of();
		return Set.copyOf(repository.findShownTmdbIds(userIds, windowStart(), startOfToday()));
	}

	@Transactional
	public void recordShown(Collection<Long> userIds, Collection<String> tmdbIds) {
		if (userIds.isEmpty() || tmdbIds.isEmpty()) return;
		Instant now = clock.instant();
		// Record the impression against every member so suppression follows each of
		// them into their other lists, not just the watchlist that served the shelf.
		for (Long userId : userIds) {
			for (String tmdbId : tmdbIds) {
				repository.upsertImpression(userId, tmdbId, now);
			}
		}
		// Rows that aged out of the window are dead weight — prune them while we
		// already hold a write transaction for these users
		repository.deleteShownBefore(userIds, windowStart());
	}

	private Instant startOfToday() {
		return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
	}

	private Instant windowStart() {
		return LocalDate.now(clock).minusDays(suppressionDays).atStartOfDay(clock.getZone()).toInstant();
	}
}
