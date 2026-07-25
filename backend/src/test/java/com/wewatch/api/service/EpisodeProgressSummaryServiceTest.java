package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.EpisodeProgressSummary;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.projection.EpisodeProgressCounts;
import com.wewatch.api.repository.projection.LastWatchedEpisode;
import com.wewatch.api.repository.projection.NextEpisode;

@ExtendWith(MockitoExtension.class)
class EpisodeProgressSummaryServiceTest {

	@Mock
	private EpisodeProgressRepository episodeProgressRepository;

	@Mock
	private TmdbTitleCacheRepository tmdbTitleCacheRepository;

	@InjectMocks
	private EpisodeProgressSummaryService service;

	// Projection stubs are lenient: not every test reads every getter
	private static EpisodeProgressCounts counts(Long entryId, long total, long watched) {
		EpisodeProgressCounts c = mock(EpisodeProgressCounts.class);
		lenient().when(c.getEntryId()).thenReturn(entryId);
		lenient().when(c.getTotal()).thenReturn(total);
		lenient().when(c.getWatched()).thenReturn(watched);
		return c;
	}

	private static LastWatchedEpisode lastWatched(Long entryId, int season, int episode) {
		LastWatchedEpisode lw = mock(LastWatchedEpisode.class);
		lenient().when(lw.getEntryId()).thenReturn(entryId);
		lenient().when(lw.getSeasonNumber()).thenReturn(season);
		lenient().when(lw.getEpisodeNumber()).thenReturn(episode);
		return lw;
	}

	private static NextEpisode nextEpisode(
		Long entryId, int season, int episode, String name, java.sql.Date airDate, Integer runtime
	) {
		NextEpisode next = mock(NextEpisode.class);
		lenient().when(next.getEntryId()).thenReturn(entryId);
		lenient().when(next.getSeasonNumber()).thenReturn(season);
		lenient().when(next.getEpisodeNumber()).thenReturn(episode);
		lenient().when(next.getName()).thenReturn(name);
		lenient().when(next.getAirDate()).thenReturn(airDate);
		lenient().when(next.getRuntimeMinutes()).thenReturn(runtime);
		return next;
	}

	// This service is TV-only, so always the "tv:" cache key (#394).
	private static TmdbTitleCache cachedShow(String tmdbId, String status) {
		TmdbTitleCache cached = new TmdbTitleCache();
		cached.setTmdbId(TmdbCacheKey.tv(tmdbId));
		cached.setStatus(status);
		return cached;
	}

	@BeforeEach
	void stubEmptyDefaults() {
		// Individual tests override the queries they care about
		lenient().when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of());
		lenient().when(episodeProgressRepository.findLastWatchedByEntryIds(anyList())).thenReturn(List.of());
		lenient().when(episodeProgressRepository.findNextEpisodeByEntryIds(anyList())).thenReturn(List.of());
	}

	@Test
	void returnsEmptyMapWithoutQueryingWhenNoEntries() {
		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of());

		assertThat(result).isEmpty();
		verify(episodeProgressRepository, never()).summarizeByEntryIds(any());
		verify(episodeProgressRepository, never()).findLastWatchedByEntryIds(any());
		verify(episodeProgressRepository, never()).findNextEpisodeByEntryIds(any());
	}

	@Test
	void buildsSummaryWithLastWatchedAndNextEpisode() {
		EpisodeProgressCounts counts = counts(5L, 10, 8);
		LastWatchedEpisode lastWatched = lastWatched(5L, 2, 5);
		NextEpisode next = nextEpisode(5L, 2, 6, "Blackwater", java.sql.Date.valueOf("2012-05-27"), 55);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));
		when(episodeProgressRepository.findLastWatchedByEntryIds(anyList())).thenReturn(List.of(lastWatched));
		when(episodeProgressRepository.findNextEpisodeByEntryIds(anyList())).thenReturn(List.of(next));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		assertThat(result).containsOnlyKeys(5L);
		EpisodeProgressSummary summary = result.get(5L);
		assertThat(summary.watchedCount()).isEqualTo(8);
		assertThat(summary.lastWatchedSeason()).isEqualTo(2);
		assertThat(summary.lastWatchedEpisode()).isEqualTo(5);
		assertThat(summary.nextSeason()).isEqualTo(2);
		assertThat(summary.nextEpisode()).isEqualTo(6);
		assertThat(summary.nextEpisodeName()).isEqualTo("Blackwater");
		assertThat(summary.nextAirDate()).isEqualTo(LocalDate.parse("2012-05-27"));
		assertThat(summary.nextRuntimeMinutes()).isEqualTo(55);
		assertThat(summary.showStatus()).isNull();
	}

	@Test
	void buildsSummaryWithNullAirDateAndMissingLastWatched() {
		EpisodeProgressCounts counts = counts(5L, 5, 3);
		NextEpisode next = nextEpisode(5L, 1, 4, "Episode 4", null, null);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));
		when(episodeProgressRepository.findNextEpisodeByEntryIds(anyList())).thenReturn(List.of(next));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		EpisodeProgressSummary summary = result.get(5L);
		assertThat(summary.lastWatchedSeason()).isNull();
		assertThat(summary.lastWatchedEpisode()).isNull();
		assertThat(summary.nextAirDate()).isNull();
		assertThat(summary.nextRuntimeMinutes()).isNull();
	}

	@Test
	void omitsEntriesWithNoWatchedEpisodes() {
		EpisodeProgressCounts counts = counts(5L, 12, 0);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		assertThat(result).isEmpty();
		verify(tmdbTitleCacheRepository, never()).findAllById(any());
	}

	@Test
	void enrichesCaughtUpEntriesWithCachedShowStatus() {
		EpisodeProgressCounts counts = counts(5L, 73, 73);
		LastWatchedEpisode lastWatched = lastWatched(5L, 8, 6);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));
		when(episodeProgressRepository.findLastWatchedByEntryIds(anyList())).thenReturn(List.of(lastWatched));
		when(tmdbTitleCacheRepository.findAllById(List.of(TmdbCacheKey.tv("1399"))))
			.thenReturn(List.of(cachedShow("1399", "Ended")));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		assertThat(result.get(5L).showStatus()).isEqualTo("Ended");
		assertThat(result.get(5L).watchedCount()).isEqualTo(73);
	}

	@Test
	void doesNotQueryShowStatusWhenNextEpisodeExists() {
		EpisodeProgressCounts counts = counts(5L, 10, 8);
		NextEpisode next = nextEpisode(5L, 2, 6, "Blackwater", null, null);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));
		when(episodeProgressRepository.findNextEpisodeByEntryIds(anyList())).thenReturn(List.of(next));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		assertThat(result.get(5L).showStatus()).isNull();
		verify(tmdbTitleCacheRepository, never()).findAllById(any());
	}

	@Test
	void leavesShowStatusNullWhenCacheHasNoStatus() {
		EpisodeProgressCounts counts = counts(5L, 73, 73);
		when(episodeProgressRepository.summarizeByEntryIds(anyList())).thenReturn(List.of(counts));
		when(tmdbTitleCacheRepository.findAllById(List.of(TmdbCacheKey.tv("1399"))))
			.thenReturn(List.of());

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(Map.of(5L, "1399"));

		assertThat(result.get(5L).showStatus()).isNull();
	}

	@Test
	void summarizesMultipleEntriesIndependently() {
		Map<Long, String> externalIds = Map.of(5L, "1399", 6L, "1396");
		EpisodeProgressCounts countsCaughtUp = counts(5L, 73, 73);
		EpisodeProgressCounts countsInProgress = counts(6L, 62, 10);
		LastWatchedEpisode lastWatchedCaughtUp = lastWatched(5L, 8, 6);
		LastWatchedEpisode lastWatchedInProgress = lastWatched(6L, 1, 10);
		NextEpisode next = nextEpisode(6L, 2, 1, "Seven Thirty-Seven", java.sql.Date.valueOf("2009-03-08"), 47);
		when(episodeProgressRepository.summarizeByEntryIds(anyList()))
			.thenReturn(List.of(countsCaughtUp, countsInProgress));
		when(episodeProgressRepository.findLastWatchedByEntryIds(anyList()))
			.thenReturn(List.of(lastWatchedCaughtUp, lastWatchedInProgress));
		when(episodeProgressRepository.findNextEpisodeByEntryIds(anyList())).thenReturn(List.of(next));
		when(tmdbTitleCacheRepository.findAllById(List.of(TmdbCacheKey.tv("1399"))))
			.thenReturn(List.of(cachedShow("1399", "Ended")));

		Map<Long, EpisodeProgressSummary> result = service.buildSummaries(externalIds);

		assertThat(result).containsOnlyKeys(5L, 6L);
		assertThat(result.get(5L).showStatus()).isEqualTo("Ended");
		assertThat(result.get(5L).nextSeason()).isNull();
		assertThat(result.get(6L).showStatus()).isNull();
		assertThat(result.get(6L).nextSeason()).isEqualTo(2);
		assertThat(result.get(6L).nextEpisode()).isEqualTo(1);
	}
}
