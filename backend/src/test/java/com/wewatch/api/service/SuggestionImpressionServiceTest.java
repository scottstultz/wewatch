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

	private static final List<Long> USER_IDS = List.of(7L);
	// A shared watchlist's shelf answers to every member's suppression (#247)
	private static final List<Long> SHARED_MEMBER_IDS = List.of(7L, 8L);
	private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");
	private static final Instant START_OF_TODAY = Instant.parse("2026-07-03T00:00:00Z");
	private static final Instant WINDOW_START = Instant.parse("2026-06-26T00:00:00Z");

	private SuggestionImpressionService service(int suppressionDays) {
		return new SuggestionImpressionService(repository, Clock.fixed(NOW, ZoneOffset.UTC), suppressionDays);
	}

	@Test
	void recentlyShownIdsQueriesPriorDaysWithinTheWindow() {
		when(repository.findShownTmdbIds(eq(USER_IDS), any(), any()))
			.thenReturn(List.of("a", "b"));

		Set<String> ids = service(7).recentlyShownIds(USER_IDS);

		assertThat(ids).containsExactlyInAnyOrder("a", "b");
		// Window ends at the start of today: impressions written earlier today must
		// not suppress the shelves that produced them (same-day stability, #231)
		verify(repository).findShownTmdbIds(USER_IDS, WINDOW_START, START_OF_TODAY);
	}

	@Test
	void recentlyShownIdsUnionsEveryMemberOfASharedWatchlist() {
		when(repository.findShownTmdbIds(eq(SHARED_MEMBER_IDS), any(), any()))
			.thenReturn(List.of("a", "b"));

		Set<String> ids = service(7).recentlyShownIds(SHARED_MEMBER_IDS);

		assertThat(ids).containsExactlyInAnyOrder("a", "b");
		verify(repository).findShownTmdbIds(SHARED_MEMBER_IDS, WINDOW_START, START_OF_TODAY);
	}

	@Test
	void recentlyShownIdsWithNoUsersTouchesNothing() {
		assertThat(service(7).recentlyShownIds(List.of())).isEmpty();

		verifyNoInteractions(repository);
	}

	@Test
	void recordShownUpsertsEachTitleForEveryUserAndPrunesExpiredRows() {
		service(7).recordShown(SHARED_MEMBER_IDS, List.of("a", "b"));

		// Fan-out: every member records the impression so suppression follows each of
		// them into their other lists, not just the shared list that served the shelf
		verify(repository).upsertImpression(7L, "a", NOW);
		verify(repository).upsertImpression(7L, "b", NOW);
		verify(repository).upsertImpression(8L, "a", NOW);
		verify(repository).upsertImpression(8L, "b", NOW);
		verify(repository).deleteShownBefore(SHARED_MEMBER_IDS, WINDOW_START);
	}

	@Test
	void recordShownWithNoTitlesTouchesNothing() {
		service(7).recordShown(USER_IDS, List.of());

		verifyNoInteractions(repository);
	}

	@Test
	void recordShownWithNoUsersTouchesNothing() {
		service(7).recordShown(List.of(), List.of("a"));

		verifyNoInteractions(repository);
	}

	@Test
	void windowLengthFollowsTheConfiguredProperty() {
		when(repository.findShownTmdbIds(eq(USER_IDS), any(), any()))
			.thenReturn(List.of());

		service(2).recentlyShownIds(USER_IDS);

		verify(repository).findShownTmdbIds(USER_IDS,
			Instant.parse("2026-07-01T00:00:00Z"), START_OF_TODAY);
	}
}
