package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.repository.SuggestionImpressionRepository;
import com.wewatch.api.repository.projection.SuggestionLastShown;

@ExtendWith(MockitoExtension.class)
class SuggestionImpressionServiceTest {

	@Mock private SuggestionImpressionRepository repository;

	private static final List<Long> USER_IDS = List.of(7L);
	// A shared watchlist's shelf answers to every member's impressions (#247)
	private static final List<Long> SHARED_MEMBER_IDS = List.of(7L, 8L);
	private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-07-03");
	private static final LocalDate WINDOW_START = LocalDate.parse("2026-06-26");

	private SuggestionImpressionService service(int suppressionDays) {
		return new SuggestionImpressionService(repository, Clock.fixed(NOW, ZoneOffset.UTC), suppressionDays);
	}

	private SuggestionLastShown shown(String tmdbId, LocalDate shownOn) {
		return new SuggestionLastShown() {
			@Override public String getTmdbId() { return tmdbId; }
			@Override public LocalDate getShownOn() { return shownOn; }
		};
	}

	@Test
	void recencyWeightsQueryPriorDaysWithinTheWindow() {
		when(repository.findLastShown(eq(USER_IDS), any(), any()))
			.thenReturn(List.of(shown("a", TODAY.minusDays(1))));

		Map<String, Double> weights = service(7).recencyWeights(USER_IDS);

		assertThat(weights).containsOnlyKeys("a");
		// Window ends at the start of today: impressions written earlier today must
		// not penalize the shelves that produced them (same-day stability, #231)
		verify(repository).findLastShown(USER_IDS, WINDOW_START, TODAY);
	}

	@Test
	void weightsDecayLinearlyFromYesterdayToTheWindowEdge() {
		when(repository.findLastShown(eq(USER_IDS), any(), any()))
			.thenReturn(List.of(
				shown("yesterday", TODAY.minusDays(1)),
				shown("mid-window", TODAY.minusDays(3)),
				shown("window-edge", TODAY.minusDays(7))));

		Map<String, Double> weights = service(7).recencyWeights(USER_IDS);

		// (window + 1 − daysSince) / window: full weight yesterday, still nonzero at
		// the edge so the last day of the window isn't a no-op, absent past it
		assertThat(weights.get("yesterday")).isEqualTo(1.0);
		assertThat(weights.get("mid-window")).isCloseTo(5.0 / 7, within(1e-9));
		assertThat(weights.get("window-edge")).isCloseTo(1.0 / 7, within(1e-9));
	}

	@Test
	void recencyWeightsWithNoUsersTouchNothing() {
		assertThat(service(7).recencyWeights(List.of())).isEmpty();

		verifyNoInteractions(repository);
	}

	@Test
	void recordShownInsertsARowPerUserAndTitleForTodayAndPrunesExpiredRows() {
		service(7).recordShown(SHARED_MEMBER_IDS, List.of("a", "b"));

		// Fan-out: every member records the impression so the penalty follows each of
		// them into their other lists, not just the shared list that served the shelf
		verify(repository).insertImpression(7L, "a", TODAY);
		verify(repository).insertImpression(7L, "b", TODAY);
		verify(repository).insertImpression(8L, "a", TODAY);
		verify(repository).insertImpression(8L, "b", TODAY);
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
		when(repository.findLastShown(eq(USER_IDS), any(), any()))
			.thenReturn(List.of(shown("a", TODAY.minusDays(2))));

		Map<String, Double> weights = service(2).recencyWeights(USER_IDS);

		verify(repository).findLastShown(USER_IDS, LocalDate.parse("2026-07-01"), TODAY);
		// Two-day window: the edge weight is 1/2
		assertThat(weights.get("a")).isEqualTo(0.5);
	}
}
