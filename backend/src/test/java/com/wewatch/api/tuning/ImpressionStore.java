package com.wewatch.api.tuning;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory stand-in for the {@code suggestion_impressions} table (#288), so
 * the harness can run the real {@link com.wewatch.api.service.SuggestionImpressionService}
 * logic (linear recency decay, per-user fan-out, day-scoped dedup) offline and
 * carry state across a simulated week. One entry per (user, title, day),
 * insert-only like the production ON CONFLICT DO NOTHING.
 */
final class ImpressionStore {

	private record Key(long userId, String tmdbId, LocalDate day) {}

	private final Set<Key> rows = new HashSet<>();

	void record(long userId, String tmdbId, LocalDate day) {
		rows.add(new Key(userId, tmdbId, day));
	}

	/** Most recent showing per title across the users within [from, to). */
	Map<String, LocalDate> lastShown(Collection<Long> userIds, LocalDate from, LocalDate to) {
		Map<String, LocalDate> latest = new HashMap<>();
		for (Key k : rows) {
			if (!userIds.contains(k.userId())) continue;
			if (k.day().isBefore(from) || !k.day().isBefore(to)) continue;
			latest.merge(k.tmdbId(), k.day(), (a, b) -> a.isAfter(b) ? a : b);
		}
		return latest;
	}

	void deleteBefore(Collection<Long> userIds, LocalDate cutoff) {
		rows.removeIf(k -> userIds.contains(k.userId()) && k.day().isBefore(cutoff));
	}
}
