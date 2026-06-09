package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;

@ExtendWith(MockitoExtension.class)
class TmdbCacheServiceTest {

	@Mock private TmdbClient tmdbClient;
	@Mock private TmdbTitleCacheRepository titleCacheRepository;
	@Mock private TmdbEpisodeCacheRepository episodeCacheRepository;

	private TmdbCacheService service;

	private static final String TMDB_ID = "1399";
	private static final int SEASON = 1;

	@BeforeEach
	void setUp() {
		service = new TmdbCacheService(tmdbClient, titleCacheRepository, episodeCacheRepository, 7L);
		lenient().when(titleCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));
		lenient().when(episodeCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));
	}

	// ── getSeasons ───────────────────────────────────────────

	@Test
	void getSeasonsCallsTmdbOnCacheMiss() {
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17",
			List.of(new TmdbTvSeason(0L, 1, "Season 1", null, null, 10, "2011-04-17", null)));
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).seasonNumber()).isEqualTo(1);
		verify(tmdbClient).getTvDetail(TMDB_ID);
		verify(titleCacheRepository).save(any(TmdbTitleCache.class));
	}

	@Test
	void getSeasonsCallsTmdbOnStaleCache() {
		TmdbTitleCache stale = freshTitleCache();
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8)); // 8 days ago
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.of(stale));
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of());
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		verify(tmdbClient).getTvDetail(TMDB_ID);
		verify(titleCacheRepository).save(any(TmdbTitleCache.class));
	}

	@Test
	void getSeasonsServesFreshCacheWithoutRefetch() {
		TmdbTitleCache fresh = freshTitleCache();
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.of(fresh));
		when(tmdbClient.getSeasons(TMDB_ID)).thenReturn(List.of(
			new TmdbTvSeason(0L, 1, "Season 1", null, null, 10, "2011-04-17", null)
		));

		List<TmdbTvSeason> result = service.getSeasons(TMDB_ID);

		assertThat(result).hasSize(1);
		verify(tmdbClient, never()).getTvDetail(anyString());
		verify(titleCacheRepository, never()).save(any());
	}

	// ── getSeasonDetail ──────────────────────────────────────

	@Test
	void getSeasonDetailCallsTmdbOnCacheMiss() {
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of());
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumberAndEpisodeNumber(anyString(), anyInt(), anyInt()))
			.thenReturn(Optional.empty());
		TmdbTvSeason season = seasonWithEpisodes();
		when(tmdbClient.getSeasonDetail(TMDB_ID, SEASON)).thenReturn(season);

		TmdbTvSeason result = service.getSeasonDetail(TMDB_ID, SEASON);

		assertThat(result.episodes()).hasSize(2);
		verify(tmdbClient).getSeasonDetail(TMDB_ID, SEASON);
		verify(episodeCacheRepository, atLeastOnce()).save(any(TmdbEpisodeCache.class));
	}

	@Test
	void getSeasonDetailCallsTmdbOnStaleCache() {
		TmdbEpisodeCache stale = freshEpisodeCache(1);
		stale.setFetchedAt(Instant.now().minusSeconds(86400 * 8));
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumber(TMDB_ID, SEASON)).thenReturn(List.of(stale));
		when(episodeCacheRepository.findByTmdbIdAndSeasonNumberAndEpisodeNumber(anyString(), anyInt(), anyInt()))
			.thenReturn(Optional.of(stale));
		when(tmdbClient.getSeasonDetail(TMDB_ID, SEASON)).thenReturn(seasonWithEpisodes());

		service.getSeasonDetail(TMDB_ID, SEASON);

		verify(tmdbClient).getSeasonDetail(TMDB_ID, SEASON);
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

	private TmdbTitleCache freshTitleCache() {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(TMDB_ID);
		c.setType("TV");
		c.setName("Game of Thrones");
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
