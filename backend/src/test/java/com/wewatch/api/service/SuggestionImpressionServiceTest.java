package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.repository.SuggestionImpressionRepository;

@ExtendWith(MockitoExtension.class)
class SuggestionImpressionServiceTest {

	@Mock private SuggestionImpressionRepository repository;

	private static final long WATCHLIST_ID = 42L;
	private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");
	private static final Instant START_OF_TODAY = Instant.parse("2026-07-03T00:00:00Z");
	private static final Instant WINDOW_START = Instant.parse("2026-06-26T00:00:00Z");

	private SuggestionImpressionService service(int suppressionDays) {
		return new SuggestionImpressionService(repository, Clock.fixed(NOW, ZoneOffset.UTC), suppressionDays);
	}

	@Test
	void recentlyShownIdsQueriesPriorDaysWithinTheWindow() {
		when(repository.findShownTmdbIds(eq(WATCHLIST_ID), any(), any()))
			.thenReturn(List.of("a", "b"));

		Set<String> ids = service(7).recentlyShownIds(WATCHLIST_ID);

		assertThat(ids).containsExactlyInAnyOrder("a", "b");
		// Window ends at the start of today: impressions written earlier today must
		// not suppress the shelves that produced them (same-day stability, #231)
		verify(repository).findShownTmdbIds(WATCHLIST_ID, WINDOW_START, START_OF_TODAY);
	}

	@Test
	void recordShownUpsertsEachTitleAndPrunesExpiredRows() {
		service(7).recordShown(WATCHLIST_ID, List.of("a", "b"));

		verify(repository).upsertImpression(WATCHLIST_ID, "a", NOW);
		verify(repository).upsertImpression(WATCHLIST_ID, "b", NOW);
		verify(repository).deleteShownBefore(WATCHLIST_ID, WINDOW_START);
	}

	@Test
	void recordShownWithNoTitlesTouchesNothing() {
		service(7).recordShown(WATCHLIST_ID, List.of());

		verifyNoInteractions(repository);
	}

	@Test
	void windowLengthFollowsTheConfiguredProperty() {
		when(repository.findShownTmdbIds(eq(WATCHLIST_ID), any(), any()))
			.thenReturn(List.of());

		service(2).recentlyShownIds(WATCHLIST_ID);

		verify(repository).findShownTmdbIds(WATCHLIST_ID,
			Instant.parse("2026-07-01T00:00:00Z"), START_OF_TODAY);
	}
}
