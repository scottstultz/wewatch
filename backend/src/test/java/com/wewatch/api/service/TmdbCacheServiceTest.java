package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.TmdbSeasonCache;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.TmdbSeasonCacheRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;

@ExtendWith(MockitoExtension.class)
class TmdbCacheServiceTest {

	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository titleCacheRepository;
	@Mock private TmdbSeasonCacheRepository seasonCacheRepository;
	@Mock private TmdbEpisodeCacheRepository episodeCacheRepository;

	private TmdbCacheService service;

	private static final String TMDB_ID = "1399";
	private static final int SEASON = 1;

	@BeforeEach
	void setUp() {
		service = new TmdbCacheService(
			tmdbClient, titleCacheRepository, seasonCacheRepository, episodeCacheRepository, 7L);
		lenient().when(titleCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));
		lenient().when(seasonCacheRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
		lenient().when(episodeCacheRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
	}

	// ── getSeasons ───────────────────────────────────────────

	@Test
	void getSeasonsCallsTmdbOnCacheMiss() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17",
			List.of(new TmdbTvSeason(0L, 1, "Season 1", null, null, 10, "2011-04-17", null)),
			"Game of Thrones", null, null, List.of(), null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).seasonNumber()).isEqualTo(1);
		verify(tmdbClient).getTvDetail(TMDB_ID);
		verify(titleCacheRepository).save(any(TmdbTitleCache.class));
		verify(seasonCacheRepository).saveAll(anyList());
	}

	@Test
	void getSeasonsCallsTmdbOnStaleCache() {
		TmdbSeasonCache stale = freshSeasonCache(1);
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8)); // 8 days ago
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of(stale));
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(), "Game of Thrones", null, null, List.of(), null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		verify(tmdbClient).getTvDetail(TMDB_ID);
		verify(titleCacheRepository).save(any(TmdbTitleCache.class));
	}

	@Test
	void getSeasonsServesFreshCacheWithoutTmdbCall() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID))
			.thenReturn(List.of(freshSeasonCache(1), freshSeasonCache(2)));

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).seasonNumber()).isEqualTo(1);
		assertThat(result.get(0).name()).isEqualTo("Season 1");
		assertThat(result.get(0).episodeCount()).isEqualTo(10);
		assertThat(result.get(0).airDate()).isEqualTo("2011-04-17");
		verifyNoInteractions(tmdbClient);
		verify(titleCacheRepository, never()).save(any());
		verify(seasonCacheRepository, never()).saveAll(any());
	}

	@Test
	void getSeasonsReturnsFreshCacheOrderedBySeasonNumber() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID))
			.thenReturn(List.of(freshSeasonCache(2), freshSeasonCache(1)));

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).extracting(TmdbTvSeason::seasonNumber).containsExactly(1, 2);
	}

	@Test
	void getSeasonsExcludesSeason0Specials() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 2, "Ended", "2011-04-17",
			List.of(
				new TmdbTvSeason(0L, 0, "Specials", null, null, 5, null, null),
				new TmdbTvSeason(3625L, 1, "Season 1", null, null, 10, "2011-04-17", null),
				new TmdbTvSeason(3626L, 2, "Season 2", null, null, 10, "2012-04-01", null)
			),
			"Game of Thrones", null, null, List.of(), null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(2);
		assertThat(result).extracting(TmdbTvSeason::seasonNumber).containsExactly(1, 2);
	}

	@Test
	void getSeasonsExcludesSeason0FromFreshCache() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID))
			.thenReturn(List.of(freshSeasonCache(0), freshSeasonCache(1)));

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).seasonNumber()).isEqualTo(1);
		verifyNoInteractions(tmdbClient);
	}

	// ── getSeasonDetail ──────────────────────────────────────

	@Test
	void getSeasonDetailCallsTmdbOnCacheMiss() {
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of());
		TmdbTvSeason season = seasonWithEpisodes();
		when(tmdbClient.getSeasonDetail(TMDB_ID, SEASON)).thenReturn(season);

		TmdbTvSeason result = service.getSeasonDetail(TMDB_ID, SEASON);

		assertThat(result.episodes()).hasSize(2);
		verify(tmdbClient).getSeasonDetail(TMDB_ID, SEASON);
		verify(episodeCacheRepository).saveAll(anyList());
	}

	@Test
	void getSeasonDetailCallsTmdbOnStaleCache() {
		TmdbEpisodeCache stale = freshEpisodeCache(1);
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8));
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of(stale));
		when(tmdbClient.getSeasonDetail(TMDB_ID, SEASON)).thenReturn(seasonWithEpisodes());

		service.getSeasonDetail(TMDB_ID, SEASON);

		verify(tmdbClient).getSeasonDetail(TMDB_ID, SEASON);
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertEpisodeCacheUpdatesExistingRowsAndBatchesWrites() {
		TmdbEpisodeCache stale = freshEpisodeCache(1);
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8));
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of(stale));
		when(tmdbClient.getSeasonDetail(TMDB_ID, SEASON)).thenReturn(seasonWithEpisodes());

		service.getSeasonDetail(TMDB_ID, SEASON);

		ArgumentCaptor<List<TmdbEpisodeCache>> captor = ArgumentCaptor.forClass(List.class);
		verify(episodeCacheRepository).saveAll(captor.capture());
		List<TmdbEpisodeCache> saved = captor.getValue();
		assertThat(saved).hasSize(2);
		// Episode 1 already had a cache row — it is updated in place, not duplicated
		assertThat(saved.get(0)).isSameAs(stale);
		assertThat(saved.get(0).getName()).isEqualTo("Winter Is Coming");
		assertThat(saved.get(1).getEpisodeNumber()).isEqualTo(2);
		verify(episodeCacheRepository, never()).save(any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertSeasonCacheUpdatesExistingRowsAndBatchesWrites() {
		TmdbSeasonCache stale = freshSeasonCache(1);
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8));
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of(stale));
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 2, "Ended", "2011-04-17",
			List.of(
				new TmdbTvSeason(3625L, 1, "Season 1 (updated)", null, null, 10, "2011-04-17", null),
				new TmdbTvSeason(3626L, 2, "Season 2", null, null, 10, "2012-04-01", null)
			),
			"Game of Thrones", null, null, List.of(), null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		ArgumentCaptor<List<TmdbSeasonCache>> captor = ArgumentCaptor.forClass(List.class);
		verify(seasonCacheRepository).saveAll(captor.capture());
		List<TmdbSeasonCache> saved = captor.getValue();
		assertThat(saved).hasSize(2);
		// Season 1 already had a cache row — it is updated in place, not duplicated
		assertThat(saved.get(0)).isSameAs(stale);
		assertThat(saved.get(0).getName()).isEqualTo("Season 1 (updated)");
		assertThat(saved.get(1).getSeasonNumber()).isEqualTo(2);
		verify(seasonCacheRepository, never()).save(any());
	}

	@Test
	void getSeasonDetailServesFreshCacheWithoutTmdbCall() {
		List<TmdbEpisodeCache> cached = List.of(freshEpisodeCache(1), freshEpisodeCache(2));
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(cached);

		TmdbTvSeason result = service.getSeasonDetail(TMDB_ID, SEASON);

		assertThat(result.seasonNumber()).isEqualTo(SEASON);
		assertThat(result.episodes()).hasSize(2);
		verify(tmdbClient, never()).getSeasonDetail(anyString(), anyInt());
	}

	@Test
	void getSeasonDetailReturnsEpisodesOrderedByEpisodeNumber() {
		TmdbEpisodeCache ep2 = freshEpisodeCache(2);
		TmdbEpisodeCache ep1 = freshEpisodeCache(1);
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of(ep2, ep1));

		TmdbTvSeason result = service.getSeasonDetail(TMDB_ID, SEASON);

		assertThat(result.episodes().get(0).episodeNumber()).isEqualTo(1);
		assertThat(result.episodes().get(1).episodeNumber()).isEqualTo(2);
	}

	// ── helpers ──────────────────────────────────────────────

	private TmdbSeasonCache freshSeasonCache(int seasonNumber) {
		TmdbSeasonCache c = new TmdbSeasonCache();
		c.setTmdbId(TMDB_ID);
		c.setSeasonNumber(seasonNumber);
		c.setName(seasonNumber == 0 ? "Specials" : "Season " + seasonNumber);
		c.setEpisodeCount(10);
		c.setAirDate(LocalDate.parse("2011-04-17"));
		c.setFetchedAt(Instant.now());
		return c;
	}

	private TmdbEpisodeCache freshEpisodeCache(int episodeNumber) {
		TmdbEpisodeCache c = new TmdbEpisodeCache();
		c.setTmdbId(TMDB_ID);
		c.setSeasonNumber(SEASON);
		c.setEpisodeNumber(episodeNumber);
		c.setName("Episode " + episodeNumber);
		c.setFetchedAt(Instant.now());
		return c;
	}

	private TmdbTvSeason seasonWithEpisodes() {
		return new TmdbTvSeason(0L, SEASON, "Season 1", null, null, 2, "2011-04-17", List.of(
			new TmdbTvEpisode(1L, 1, "Winter Is Coming", null, "2011-04-17", null, 62),
			new TmdbTvEpisode(2L, 2, "The Kingsroad", null, "2011-04-24", null, 56)
		));
	}
}
