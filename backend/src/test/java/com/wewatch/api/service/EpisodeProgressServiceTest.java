package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;

class EpisodeProgressServiceTest {

	private EpisodeProgressRepository episodeProgressRepository;
	private WatchlistEntryService watchlistEntryService;
	private TitleService titleService;
	private EpisodeProgressService service;

	private static final Instant EPOCH = Instant.EPOCH;

	private static final WatchlistEntry TV_ENTRY = new WatchlistEntry(
		1L, 10L, 100L, WatchStatus.WATCHING, EPOCH, EPOCH, EPOCH, null
	);

	private static final WatchlistEntry MOVIE_ENTRY = new WatchlistEntry(
		2L, 10L, 200L, WatchStatus.WATCHING, EPOCH, EPOCH, EPOCH, null
	);

	private static final Title TV_TITLE = new Title(
		100L, "1399", "TMDB", TitleType.TV, "Game of Thrones", null, null, null, EPOCH, EPOCH
	);

	private static final Title MOVIE_TITLE = new Title(
		200L, "603", "TMDB", TitleType.MOVIE, "The Matrix", null, null, null, EPOCH, EPOCH
	);

	@BeforeEach
	void setUp() {
		episodeProgressRepository = Mockito.mock(EpisodeProgressRepository.class);
		watchlistEntryService = Mockito.mock(WatchlistEntryService.class);
		titleService = Mockito.mock(TitleService.class);
		service = new EpisodeProgressService(episodeProgressRepository, watchlistEntryService, titleService);

		when(watchlistEntryService.findById(10L, 1L)).thenReturn(TV_ENTRY);
		when(watchlistEntryService.findById(10L, 2L)).thenReturn(MOVIE_ENTRY);
		when(titleService.findById(100L)).thenReturn(TV_TITLE);
		when(titleService.findById(200L)).thenReturn(MOVIE_TITLE);
	}

	// ─── getProgress ─────────────────────────────────────────────────────────

	@Test
	void getProgressReturnsAllForEntry() {
		EpisodeProgress ep1 = new EpisodeProgress(1L, 1L, 1, 1, true, EPOCH);
		EpisodeProgress ep2 = new EpisodeProgress(2L, 1L, 1, 2, false, null);
		when(episodeProgressRepository.findByWatchlistEntryId(1L)).thenReturn(List.of(ep1, ep2));

		List<EpisodeProgress> result = service.getProgress(10L, 1L, null);

		assertThat(result).hasSize(2);
		verify(episodeProgressRepository).findByWatchlistEntryId(1L);
	}

	@Test
	void getProgressFiltersBySeason() {
		EpisodeProgress ep1 = new EpisodeProgress(1L, 1L, 2, 1, true, EPOCH);
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(1L, 2)).thenReturn(List.of(ep1));

		List<EpisodeProgress> result = service.getProgress(10L, 1L, 2);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getSeasonNumber()).isEqualTo(2);
	}

	@Test
	void getProgressThrowsForMovieEntry() {
		assertThatThrownBy(() -> service.getProgress(10L, 2L, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Episode tracking is only available for TV shows");
	}

	// ─── toggleEpisode ───────────────────────────────────────────────────────

	@Test
	void toggleEpisodeCreatesNewRowWhenNotFound() {
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(1L, 1, 3))
			.thenReturn(Optional.empty());
		when(episodeProgressRepository.save(any(EpisodeProgress.class)))
			.thenAnswer(inv -> {
				EpisodeProgress ep = inv.getArgument(0);
				ep.setId(99L);
				return ep;
			});

		EpisodeProgress result = service.toggleEpisode(10L, 1L, 1, 3);

		assertThat(result.getWatched()).isTrue();
		assertThat(result.getWatchedAt()).isNotNull();
		assertThat(result.getSeasonNumber()).isEqualTo(1);
		assertThat(result.getEpisodeNumber()).isEqualTo(3);

		ArgumentCaptor<EpisodeProgress> captor = ArgumentCaptor.forClass(EpisodeProgress.class);
		verify(episodeProgressRepository).save(captor.capture());
		assertThat(captor.getValue().getWatchlistEntryId()).isEqualTo(1L);
	}

	@Test
	void toggleEpisodeFlipsWatchedToUnwatched() {
		EpisodeProgress existing = new EpisodeProgress(5L, 1L, 1, 1, true, EPOCH);
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(1L, 1, 1))
			.thenReturn(Optional.of(existing));
		when(episodeProgressRepository.save(any(EpisodeProgress.class))).thenAnswer(inv -> inv.getArgument(0));

		EpisodeProgress result = service.toggleEpisode(10L, 1L, 1, 1);

		assertThat(result.getWatched()).isFalse();
		assertThat(result.getWatchedAt()).isNull();
	}

	@Test
	void toggleEpisodeFlipsUnwatchedToWatched() {
		EpisodeProgress existing = new EpisodeProgress(5L, 1L, 1, 2, false, null);
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(1L, 1, 2))
			.thenReturn(Optional.of(existing));
		when(episodeProgressRepository.save(any(EpisodeProgress.class))).thenAnswer(inv -> inv.getArgument(0));

		EpisodeProgress result = service.toggleEpisode(10L, 1L, 1, 2);

		assertThat(result.getWatched()).isTrue();
		assertThat(result.getWatchedAt()).isNotNull();
	}

	@Test
	void toggleEpisodeThrowsForMovieEntry() {
		assertThatThrownBy(() -> service.toggleEpisode(10L, 2L, 1, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Episode tracking is only available for TV shows");
	}

	// ─── bulkMarkSeason ──────────────────────────────────────────────────────

	@Test
	void bulkMarkSeasonUpdatesAndReturnsSeason() {
		when(episodeProgressRepository.updateSeasonWatched(eq(1L), eq(1), eq(true), any(Instant.class)))
			.thenReturn(3);
		EpisodeProgress ep1 = new EpisodeProgress(1L, 1L, 1, 1, true, EPOCH);
		EpisodeProgress ep2 = new EpisodeProgress(2L, 1L, 1, 2, true, EPOCH);
		EpisodeProgress ep3 = new EpisodeProgress(3L, 1L, 1, 3, true, EPOCH);
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(1L, 1))
			.thenReturn(List.of(ep1, ep2, ep3));

		List<EpisodeProgress> result = service.bulkMarkSeason(10L, 1L, 1, true);

		assertThat(result).hasSize(3);
		assertThat(result).allMatch(EpisodeProgress::getWatched);
		verify(episodeProgressRepository).updateSeasonWatched(eq(1L), eq(1), eq(true), any(Instant.class));
	}

	@Test
	void bulkMarkSeasonUnwatchedClearsTimestamp() {
		when(episodeProgressRepository.updateSeasonWatched(1L, 2, false, null))
			.thenReturn(2);
		when(episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(1L, 2))
			.thenReturn(List.of(
				new EpisodeProgress(4L, 1L, 2, 1, false, null),
				new EpisodeProgress(5L, 1L, 2, 2, false, null)
			));

		List<EpisodeProgress> result = service.bulkMarkSeason(10L, 1L, 2, false);

		assertThat(result).hasSize(2);
		assertThat(result).noneMatch(EpisodeProgress::getWatched);
		verify(episodeProgressRepository).updateSeasonWatched(1L, 2, false, null);
	}

	@Test
	void bulkMarkSeasonThrowsForMovieEntry() {
		assertThatThrownBy(() -> service.bulkMarkSeason(10L, 2L, 1, true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Episode tracking is only available for TV shows");
	}
}
