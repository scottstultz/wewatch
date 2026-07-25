package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import com.wewatch.api.dto.TonightPickResponse;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.projection.NextEpisode;

@ExtendWith(MockitoExtension.class)
class TonightServiceTest {

	private static final Long WATCHLIST_ID = 7L;

	@Mock
	private WatchlistEntryRepository watchlistEntryRepository;

	@Mock
	private TitleService titleService;

	@Mock
	private TmdbTitleCacheRepository titleCacheRepository;

	@Mock
	private EpisodeProgressRepository episodeProgressRepository;

	@InjectMocks
	private TonightService service;

	/** Entry ids double as title ids here — one title per entry keeps the fixtures readable. */
	private final Map<Long, Title> titles = new HashMap<>();

	@BeforeEach
	void setUpDefaults() {
		lenient().when(titleService.findByIds(anyCollection())).thenAnswer(invocation -> {
			Collection<?> ids = invocation.getArgument(0);
			Map<Long, Title> found = new HashMap<>();
			ids.forEach(id -> {
				Title title = titles.get((Long) id);
				if (title != null) found.put((Long) id, title);
			});
			return found;
		});
		lenient().when(titleCacheRepository.findAllById(any())).thenReturn(List.of());
		lenient().when(episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(anyList()))
			.thenReturn(List.of());
	}

	// ─── fixtures ────────────────────────────────────────────────────────────

	private WatchlistEntry entry(Long id, TitleType type, String externalId, WatchStatus status) {
		titles.put(id, new Title(
			id, externalId, "tmdb", type, "Title " + id, null, null, null, Instant.EPOCH, Instant.EPOCH
		));
		return new WatchlistEntry(id, WATCHLIST_ID, id, status, Instant.EPOCH, Instant.EPOCH, null, null);
	}

	private void givenEntries(WatchlistEntry... entries) {
		Page<WatchlistEntry> page = new PageImpl<>(List.of(entries));
		when(watchlistEntryRepository.findByWatchlistId(any(), any(), any())).thenReturn(page);
	}

	// Movie-only in this file, so always the "movie:" cache key (#394) — TonightService's movie
	// path keys its batch read the same way.
	private static TmdbTitleCache cacheRow(String tmdbId, Integer runtimeMinutes) {
		TmdbTitleCache row = new TmdbTitleCache();
		row.setTmdbId(TmdbCacheKey.movie(tmdbId));
		row.setRuntimeMinutes(runtimeMinutes);
		return row;
	}

	private void givenMovieRuntimes(Map<String, Integer> runtimeByTmdbId) {
		when(titleCacheRepository.findAllById(any())).thenReturn(
			runtimeByTmdbId.entrySet().stream().map(e -> cacheRow(e.getKey(), e.getValue())).toList()
		);
	}

	private static NextEpisode nextEpisode(Long entryId, int season, int episode, Integer runtime) {
		NextEpisode next = mock(NextEpisode.class);
		lenient().when(next.getEntryId()).thenReturn(entryId);
		lenient().when(next.getSeasonNumber()).thenReturn(season);
		lenient().when(next.getEpisodeNumber()).thenReturn(episode);
		lenient().when(next.getRuntimeMinutes()).thenReturn(runtime);
		return next;
	}

	// ─── movies ──────────────────────────────────────────────────────────────

	@Test
	void offersAMovieWhoseRuntimeFitsTheWindow() {
		givenEntries(entry(1L, TitleType.MOVIE, "603", WatchStatus.WANT_TO_WATCH));
		givenMovieRuntimes(Map.of("603", 88));

		List<TonightPickResponse> picks = service.fitsWithin(WATCHLIST_ID, 90);

		assertThat(picks).singleElement().satisfies(pick -> {
			assertThat(pick.entryId()).isEqualTo(1L);
			assertThat(pick.type()).isEqualTo(TitleType.MOVIE);
			assertThat(pick.runtimeMinutes()).isEqualTo(88);
			assertThat(pick.nextSeason()).isNull();
			assertThat(pick.nextEpisode()).isNull();
		});
	}

	@Test
	void leavesOutAMovieLongerThanTheWindow() {
		givenEntries(entry(1L, TitleType.MOVIE, "603", WatchStatus.WANT_TO_WATCH));
		givenMovieRuntimes(Map.of("603", 136));

		assertThat(service.fitsWithin(WATCHLIST_ID, 90)).isEmpty();
	}

	@Test
	void theWindowIsInclusive() {
		givenEntries(entry(1L, TitleType.MOVIE, "603", WatchStatus.WANT_TO_WATCH));
		givenMovieRuntimes(Map.of("603", 90));

		assertThat(service.fitsWithin(WATCHLIST_ID, 90)).hasSize(1);
	}

	// ─── TV ──────────────────────────────────────────────────────────────────

	@Test
	void judgesAShowOnItsNextUnwatchedEpisode() {
		givenEntries(entry(2L, TitleType.TV, "1396", WatchStatus.WATCHING));
		NextEpisode next = nextEpisode(2L, 3, 7, 47);
		when(episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(List.of(2L)))
			.thenReturn(List.of(next));

		List<TonightPickResponse> picks = service.fitsWithin(WATCHLIST_ID, 60);

		assertThat(picks).singleElement().satisfies(pick -> {
			assertThat(pick.entryId()).isEqualTo(2L);
			assertThat(pick.type()).isEqualTo(TitleType.TV);
			assertThat(pick.runtimeMinutes()).isEqualTo(47);
			assertThat(pick.nextSeason()).isEqualTo(3);
			assertThat(pick.nextEpisode()).isEqualTo(7);
		});
		// The show's own runtime column is not what a show is judged on
		verify(titleCacheRepository, never()).findAllById(any());
	}

	@Test
	void offersEpisodeOneOfAShowNeverStarted() {
		givenEntries(entry(2L, TitleType.TV, "1396", WatchStatus.WANT_TO_WATCH));
		// The COALESCE branch of findNextUnwatchedEpisodeByEntryIds: nothing watched, so pos 1
		NextEpisode next = nextEpisode(2L, 1, 1, 58);
		when(episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(List.of(2L)))
			.thenReturn(List.of(next));

		List<TonightPickResponse> picks = service.fitsWithin(WATCHLIST_ID, 60);

		assertThat(picks).singleElement().satisfies(pick -> {
			assertThat(pick.nextSeason()).isEqualTo(1);
			assertThat(pick.nextEpisode()).isEqualTo(1);
			assertThat(pick.runtimeMinutes()).isEqualTo(58);
		});
	}

	@Test
	void leavesOutAShowWithNoNextEpisode() {
		// Caught up, or episodes never cached — either way there is nothing to put on
		givenEntries(entry(2L, TitleType.TV, "1396", WatchStatus.WATCHING));

		assertThat(service.fitsWithin(WATCHLIST_ID, 120)).isEmpty();
	}

	// ─── unknown runtimes ────────────────────────────────────────────────────

	@Test
	void leavesOutAMovieWithNoKnownRuntime() {
		givenEntries(
			entry(1L, TitleType.MOVIE, "603", WatchStatus.WANT_TO_WATCH),
			entry(3L, TitleType.MOVIE, "604", WatchStatus.WANT_TO_WATCH)
		);
		// 604 has a cache row with a null runtime; 603 has no cache row at all
		when(titleCacheRepository.findAllById(any())).thenReturn(List.of(cacheRow("604", null)));

		assertThat(service.fitsWithin(WATCHLIST_ID, 600)).isEmpty();
	}

	@Test
	void leavesOutAShowWhoseNextEpisodeHasNoRuntime() {
		givenEntries(entry(2L, TitleType.TV, "1396", WatchStatus.WATCHING));
		NextEpisode next = nextEpisode(2L, 1, 1, null);
		when(episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(List.of(2L)))
			.thenReturn(List.of(next));

		assertThat(service.fitsWithin(WATCHLIST_ID, 600)).isEmpty();
	}

	// ─── scope and ordering ──────────────────────────────────────────────────

	@Test
	void ignoresFinishedEntries() {
		givenEntries(entry(1L, TitleType.MOVIE, "603", WatchStatus.WATCHED));

		assertThat(service.fitsWithin(WATCHLIST_ID, 600)).isEmpty();
		verify(titleCacheRepository, never()).findAllById(any());
	}

	@Test
	void returnsShortestFirstAcrossMoviesAndShows() {
		givenEntries(
			entry(1L, TitleType.MOVIE, "603", WatchStatus.WANT_TO_WATCH),
			entry(2L, TitleType.TV, "1396", WatchStatus.WATCHING)
		);
		givenMovieRuntimes(Map.of("603", 88));
		NextEpisode next = nextEpisode(2L, 3, 7, 47);
		when(episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(List.of(2L)))
			.thenReturn(List.of(next));

		assertThat(service.fitsWithin(WATCHLIST_ID, 90))
			.extracting(TonightPickResponse::runtimeMinutes)
			.containsExactly(47, 88);
	}

	@Test
	void rejectsAWindowOutsideTheAllowedRange() {
		assertThatThrownBy(() -> service.fitsWithin(WATCHLIST_ID, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxMinutes");
		assertThatThrownBy(() -> service.fitsWithin(WATCHLIST_ID, 601))
			.isInstanceOf(IllegalArgumentException.class);
		verify(watchlistEntryRepository, never()).findByWatchlistId(any(), any(), any());
	}
}
