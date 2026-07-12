package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.ReturningEpisodeResponse;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.projection.ReturningEpisode;

/**
 * The date window is the whole feature, so it is what these tests pin down.
 *
 * <p>The suite mocks every repository — no test in this project executes SQL — so the
 * window's boundaries are asserted on the {@code from}/{@code to} arguments the service
 * hands the query, and the earliest-per-show pick is asserted on rows the service
 * actually walks. Both live in Java for exactly that reason.
 */
@ExtendWith(MockitoExtension.class)
class ReturningEpisodeServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 7, 12);
	private static final Long WATCHLIST_ID = 10L;

	@Mock
	private TmdbEpisodeCacheRepository tmdbEpisodeCacheRepository;

	@Captor
	private ArgumentCaptor<LocalDate> fromCaptor;

	@Captor
	private ArgumentCaptor<LocalDate> toCaptor;

	private ReturningEpisodeService service;

	@BeforeEach
	void setUp() {
		Clock fixed = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
		service = new ReturningEpisodeService(tmdbEpisodeCacheRepository, fixed);
	}

	// Projection stubs are lenient: not every test reads every getter
	private static ReturningEpisode episode(Long entryId, int season, int number, LocalDate airDate) {
		ReturningEpisode e = mock(ReturningEpisode.class);
		lenient().when(e.getEntryId()).thenReturn(entryId);
		lenient().when(e.getExternalId()).thenReturn("tv-" + entryId);
		lenient().when(e.getExternalSource()).thenReturn("tmdb");
		lenient().when(e.getShowName()).thenReturn("Show " + entryId);
		lenient().when(e.getPosterUrl()).thenReturn("https://img/" + entryId + ".jpg");
		lenient().when(e.getSeasonNumber()).thenReturn(season);
		lenient().when(e.getEpisodeNumber()).thenReturn(number);
		lenient().when(e.getEpisodeName()).thenReturn("S" + season + "E" + number);
		lenient().when(e.getAirDate()).thenReturn(airDate == null ? null : java.sql.Date.valueOf(airDate));
		lenient().when(e.getRuntimeMinutes()).thenReturn(42);
		return e;
	}

	private void givenUpcoming(ReturningEpisode... episodes) {
		when(tmdbEpisodeCacheRepository.findUpcomingByWatchlistId(any(), any(), any()))
			.thenReturn(List.of(episodes));
	}

	// ─── The window ──────────────────────────────────────────────────────────

	@Test
	void queriesTodayThroughSevenDaysInclusive() {
		givenUpcoming();

		service.returningWithin(WATCHLIST_ID, 7);

		verify(tmdbEpisodeCacheRepository)
			.findUpcomingByWatchlistId(eq(WATCHLIST_ID), fromCaptor.capture(), toCaptor.capture());
		// Both ends inclusive (the query uses BETWEEN): an episode airing today is
		// "returning this week", and so is one airing exactly seven days out. Anything
		// at +8, or any date in the past, falls outside these bounds.
		assertThat(fromCaptor.getValue()).isEqualTo(TODAY);
		assertThat(toCaptor.getValue()).isEqualTo(TODAY.plusDays(7));
	}

	@Test
	void widensTheWindowForACustomDayCount() {
		givenUpcoming();

		service.returningWithin(WATCHLIST_ID, 30);

		verify(tmdbEpisodeCacheRepository)
			.findUpcomingByWatchlistId(eq(WATCHLIST_ID), fromCaptor.capture(), toCaptor.capture());
		assertThat(fromCaptor.getValue()).isEqualTo(TODAY);
		assertThat(toCaptor.getValue()).isEqualTo(TODAY.plusDays(30));
	}

	@Test
	void keepsEpisodesOnBothEdgesOfTheWindow() {
		givenUpcoming(
			episode(1L, 2, 4, TODAY),
			episode(2L, 1, 9, TODAY.plusDays(7))
		);

		List<ReturningEpisodeResponse> result = service.returningWithin(WATCHLIST_ID, 7);

		assertThat(result).extracting(ReturningEpisodeResponse::airDate)
			.containsExactly(TODAY, TODAY.plusDays(7));
	}

	@Test
	void rejectsADayCountOutsideTheAllowedRange() {
		assertThatThrownBy(() -> service.returningWithin(WATCHLIST_ID, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("days must be between 1 and 30");
		assertThatThrownBy(() -> service.returningWithin(WATCHLIST_ID, 31))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("days must be between 1 and 30");

		verify(tmdbEpisodeCacheRepository, never()).findUpcomingByWatchlistId(any(), any(), any());
	}

	// ─── One row per show ────────────────────────────────────────────────────

	@Test
	void returnsOnlyTheSoonestEpisodePerShow() {
		// A show mid-season has several episodes inside the window; only the next one is
		// news. The query returns air-date order, so first-seen wins.
		givenUpcoming(
			episode(1L, 2, 4, TODAY.plusDays(1)),
			episode(1L, 2, 5, TODAY.plusDays(6)),
			episode(2L, 3, 1, TODAY.plusDays(3))
		);

		List<ReturningEpisodeResponse> result = service.returningWithin(WATCHLIST_ID, 7);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).entryId()).isEqualTo(1L);
		assertThat(result.get(0).episodeNumber()).isEqualTo(4);
		assertThat(result.get(0).airDate()).isEqualTo(TODAY.plusDays(1));
		assertThat(result.get(1).entryId()).isEqualTo(2L);
	}

	@Test
	void preservesTheQuerysSoonestFirstOrdering() {
		givenUpcoming(
			episode(1L, 1, 1, TODAY.plusDays(2)),
			episode(2L, 1, 1, TODAY.plusDays(5))
		);

		List<ReturningEpisodeResponse> result = service.returningWithin(WATCHLIST_ID, 7);

		assertThat(result).extracting(ReturningEpisodeResponse::entryId).containsExactly(1L, 2L);
	}

	// ─── Absent data ─────────────────────────────────────────────────────────

	@Test
	void skipsEpisodesWithNoAirDate() {
		// BETWEEN already drops null air dates in SQL; this guards the mapper, which
		// would otherwise NPE on toLocalDate() if the query ever loosened.
		givenUpcoming(
			episode(1L, 2, 4, null),
			episode(2L, 1, 1, TODAY.plusDays(2))
		);

		List<ReturningEpisodeResponse> result = service.returningWithin(WATCHLIST_ID, 7);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).entryId()).isEqualTo(2L);
	}

	@Test
	void returnsEmptyWhenNothingIsAiringSoon() {
		givenUpcoming();

		assertThat(service.returningWithin(WATCHLIST_ID, 7)).isEmpty();
	}

	// ─── Mapping ─────────────────────────────────────────────────────────────

	@Test
	void mapsTheShowAndEpisodeOntoTheResponse() {
		givenUpcoming(episode(7L, 2, 4, TODAY.plusDays(3)));

		ReturningEpisodeResponse row = service.returningWithin(WATCHLIST_ID, 7).get(0);

		assertThat(row.entryId()).isEqualTo(7L);
		assertThat(row.externalId()).isEqualTo("tv-7");
		assertThat(row.externalSource()).isEqualTo("tmdb");
		assertThat(row.showName()).isEqualTo("Show 7");
		assertThat(row.posterUrl()).isEqualTo("https://img/7.jpg");
		assertThat(row.seasonNumber()).isEqualTo(2);
		assertThat(row.episodeNumber()).isEqualTo(4);
		assertThat(row.episodeName()).isEqualTo("S2E4");
		assertThat(row.airDate()).isEqualTo(TODAY.plusDays(3));
		assertThat(row.runtimeMinutes()).isEqualTo(42);
	}
}
