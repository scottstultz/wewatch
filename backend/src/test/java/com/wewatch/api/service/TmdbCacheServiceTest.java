package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.CachedKeyword;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.TmdbSeasonCache;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.TmdbSeasonCacheRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.tmdb.TmdbCastMember;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbCredits;
import com.wewatch.api.tmdb.TmdbCrewMember;
import com.wewatch.api.tmdb.TmdbKeyword;
import com.wewatch.api.tmdb.TmdbMovieDetail;
import com.wewatch.api.tmdb.TmdbRegionWatchProviders;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;
import com.wewatch.api.tmdb.TmdbWatchProvider;
import com.wewatch.api.tmdb.TmdbWatchProviders;

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
			"Game of Thrones", null, null, List.of(), null, null, null, null, null);
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
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(), "Game of Thrones", null, null, List.of(), null, null, null, null, null);
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
			"Game of Thrones", null, null, List.of(), null, null, null, null, null);
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

	// ── credits caching (#269) ───────────────────────────────

	@Test
	void upsertStoresTopBilledCastAndDirectorsFromCredits() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbCredits credits = new TmdbCredits(
			List.of(
				new TmdbCastMember(6L, "Sixth Billed", null, null, 5),
				new TmdbCastMember(1L, "Lead", null, null, 0),
				new TmdbCastMember(2L, "Second", null, null, 1),
				new TmdbCastMember(3L, "Third", null, null, 2),
				new TmdbCastMember(4L, "Fourth", null, null, 3),
				new TmdbCastMember(5L, "Fifth", null, null, 4)),
			List.of(
				new TmdbCrewMember(100L, "The Director", "Director"),
				new TmdbCrewMember(101L, "The Producer", "Producer"),
				new TmdbCrewMember(100L, "The Director", "Director")));
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(),
			"Game of Thrones", null, null, List.of(), null, null, credits, null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		ArgumentCaptor<TmdbTitleCache> captor = ArgumentCaptor.forClass(TmdbTitleCache.class);
		verify(titleCacheRepository).save(captor.capture());
		// Top five by billing order — out-of-order input is sorted, the sixth dropped
		assertThat(captor.getValue().getTopCast()).containsExactly(
			new CachedPerson(1, "Lead"), new CachedPerson(2, "Second"),
			new CachedPerson(3, "Third"), new CachedPerson(4, "Fourth"),
			new CachedPerson(5, "Fifth"));
		// Director job only, duplicate directing credits collapsed to one person
		assertThat(captor.getValue().getDirectors())
			.containsExactly(new CachedPerson(100, "The Director"));
	}

	@Test
	void nullCreditsBlockKeepsPreviouslyCachedPeople() {
		TmdbTitleCache existing = new TmdbTitleCache();
		existing.setTmdbId(TMDB_ID);
		existing.setTopCast(List.of(new CachedPerson(1, "Lead")));
		existing.setDirectors(List.of(new CachedPerson(100, "The Director")));
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.of(existing));
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(),
			"Game of Thrones", null, null, List.of(), null, null, null, null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		ArgumentCaptor<TmdbTitleCache> captor = ArgumentCaptor.forClass(TmdbTitleCache.class);
		verify(titleCacheRepository).save(captor.capture());
		// A TMDB response without the appended credits leaves the people signal alone
		assertThat(captor.getValue().getTopCast()).containsExactly(new CachedPerson(1, "Lead"));
		assertThat(captor.getValue().getDirectors()).containsExactly(new CachedPerson(100, "The Director"));
	}

	@Test
	void prewarmStoresKeywordIdsAndNames() {
		// Ids keep feeding the scoring CSV; names land in the JSON column that
		// labels keyword-seeded shelves (#271)
		TmdbTitleCache existing = new TmdbTitleCache();
		existing.setTmdbId(TMDB_ID);
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.of(existing));
		when(tmdbClient.getMovieDetail(TMDB_ID)).thenReturn(new TmdbMovieDetail(
			1399L, "Inception", null, null, null, null, List.of(), null, null, null, null, null, null, null));
		when(tmdbClient.getKeywords(TitleType.MOVIE, TMDB_ID)).thenReturn(List.of(
			new TmdbKeyword(9882, "space race"),
			new TmdbKeyword(4565, "dystopia")));

		service.prewarmMovie(TMDB_ID);

		ArgumentCaptor<TmdbTitleCache> captor = ArgumentCaptor.forClass(TmdbTitleCache.class);
		// One save from the detail upsert, one from the keyword write — the
		// keyword pass is the last
		verify(titleCacheRepository, times(2)).save(captor.capture());
		assertThat(captor.getValue().getKeywordIds()).containsExactly(9882, 4565);
		assertThat(captor.getValue().getKeywords()).containsExactly(
			new CachedKeyword(9882, "space race"),
			new CachedKeyword(4565, "dystopia"));
	}

	@Test
	void watchProvidersAreCachedPerRegionFlatrateOnly() {
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.empty());
		TmdbWatchProviders providers = new TmdbWatchProviders(Map.of(
			"US", new TmdbRegionWatchProviders(List.of(
				new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0),
				new TmdbWatchProvider(9, "Prime Video", "/p.jpg", 1))),
			// Rent/buy-only region: no flatrate offers → not stored (#270)
			"GB", new TmdbRegionWatchProviders(null)));
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(),
			"Game of Thrones", null, null, List.of(), null, null, null, providers, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		ArgumentCaptor<TmdbTitleCache> captor = ArgumentCaptor.forClass(TmdbTitleCache.class);
		verify(titleCacheRepository).save(captor.capture());
		assertThat(captor.getValue().getWatchProviders())
			.containsOnlyKeys("US")
			.containsEntry("US", List.of(8, 9));
	}

	@Test
	void nullWatchProvidersBlockKeepsPreviouslyCachedOnes() {
		TmdbTitleCache existing = new TmdbTitleCache();
		existing.setTmdbId(TMDB_ID);
		existing.setWatchProviders(Map.of("US", List.of(8)));
		when(seasonCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(List.of());
		when(titleCacheRepository.findByTmdbId(TMDB_ID)).thenReturn(Optional.of(existing));
		TmdbTvDetail detail = new TmdbTvDetail(1399L, 8, "Ended", "2011-04-17", List.of(),
			"Game of Thrones", null, null, List.of(), null, null, null, null, null);
		when(tmdbClient.getTvDetail(TMDB_ID)).thenReturn(detail);

		service.getSeasons(TMDB_ID);

		ArgumentCaptor<TmdbTitleCache> captor = ArgumentCaptor.forClass(TmdbTitleCache.class);
		verify(titleCacheRepository).save(captor.capture());
		// A TMDB response without the appended block leaves availability alone
		assertThat(captor.getValue().getWatchProviders()).containsEntry("US", List.of(8));
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
			"Game of Thrones", null, null, List.of(), null, null, null, null, null);
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

	// ── genre and provider ids for a batch of titles (#381, #392) ──

	private static final String REGION = "US";

	@Test
	void cacheIdsByTitleIdReadsTheCacheOnceForTheWholeBatch() {
		// One findAllById for the page is the point: a 20-entry Library page must not cost 20
		// cache reads.
		Title matrix = title(1L, "603", TitleType.MOVIE);
		Title thrones = title(2L, "1399", TitleType.TV);
		when(titleCacheRepository.findAllById(anyIterable())).thenReturn(List.of(
			cacheRow("603", "MOVIE", List.of(28, 878), Map.of(REGION, List.of(8))),
			cacheRow("1399", "TV", List.of(10765, 18), Map.of(REGION, List.of(1899)))));

		Map<Long, TmdbCacheService.TitleCacheIds> cacheIds =
			service.cacheIdsByTitleId(List.of(matrix, thrones), REGION, List.of(8, 1899));

		assertThat(cacheIds).containsOnly(
			Map.entry(1L, new TmdbCacheService.TitleCacheIds(List.of(28, 878), List.of(8))),
			Map.entry(2L, new TmdbCacheService.TitleCacheIds(List.of(10765, 18), List.of(1899))));
		verify(titleCacheRepository, times(1)).findAllById(anyIterable());
	}

	@Test
	void cacheIdsByTitleIdYieldsEmptyForAnUncachedTitle() {
		// "We haven't cached this title yet" is not an error, and a caller filtering on genre
		// or provider wants to skip the entry rather than handle a null.
		when(titleCacheRepository.findAllById(anyIterable())).thenReturn(List.of());

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "603", TitleType.MOVIE)), REGION, List.of(8)))
			.containsOnly(Map.entry(1L, TmdbCacheService.TitleCacheIds.EMPTY));
	}

	@Test
	void cacheIdsByTitleIdYieldsEmptyWhenTheRowHasNoGenresOrProviders() {
		when(titleCacheRepository.findAllById(anyIterable()))
			.thenReturn(List.of(cacheRow("603", "MOVIE", null, null)));

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "603", TitleType.MOVIE)), REGION, List.of(8)))
			.containsOnly(Map.entry(1L, TmdbCacheService.TitleCacheIds.EMPTY));
	}

	@Test
	void cacheIdsByTitleIdRejectsARowCachedForTheOtherMedium() {
		// tmdb_title_cache is keyed by a bare TMDB id with no medium namespace (V9), so movie 1399
		// and TV 1399 share one row and the last prewarm wins. Genre and provider ids are both
		// medium-specific: TV's 10759 "Action & Adventure" is not a movie genre at all, and TV's
		// providers aren't necessarily where the movie streams. Fail closed rather than mislabel.
		when(titleCacheRepository.findAllById(anyIterable()))
			.thenReturn(List.of(cacheRow("1399", "TV", List.of(10759), Map.of(REGION, List.of(8)))));

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "1399", TitleType.MOVIE)), REGION, List.of(8)))
			.containsOnly(Map.entry(1L, TmdbCacheService.TitleCacheIds.EMPTY));
	}

	@Test
	void cacheIdsByTitleIdSkipsTheCacheEntirelyForAnEmptyBatch() {
		assertThat(service.cacheIdsByTitleId(List.of(), REGION, List.of(8))).isEmpty();
		verify(titleCacheRepository, never()).findAllById(anyIterable());
	}

	@Test
	void cacheIdsByTitleIdIntersectsProvidersWithTheCallersServices() {
		// The row streams on providers 8 and 9 in this region; the caller only has 8 configured.
		// A badge means "on a service you have", not "available at all" (#392).
		when(titleCacheRepository.findAllById(anyIterable()))
			.thenReturn(List.of(cacheRow("603", "MOVIE", List.of(28), Map.of(REGION, List.of(8, 9)))));

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "603", TitleType.MOVIE)), REGION, List.of(8)))
			.containsOnly(Map.entry(1L, new TmdbCacheService.TitleCacheIds(List.of(28), List.of(8))));
	}

	@Test
	void cacheIdsByTitleIdYieldsNoProvidersForARegionTheRowDoesNotCarry() {
		when(titleCacheRepository.findAllById(anyIterable()))
			.thenReturn(List.of(cacheRow("603", "MOVIE", List.of(28), Map.of("GB", List.of(8)))));

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "603", TitleType.MOVIE)), REGION, List.of(8)))
			.containsOnly(Map.entry(1L, new TmdbCacheService.TitleCacheIds(List.of(28), List.of())));
	}

	@Test
	void cacheIdsByTitleIdYieldsNoProvidersWhenTheCallerHasNoneConfigured() {
		when(titleCacheRepository.findAllById(anyIterable()))
			.thenReturn(List.of(cacheRow("603", "MOVIE", List.of(28), Map.of(REGION, List.of(8)))));

		assertThat(service.cacheIdsByTitleId(List.of(title(1L, "603", TitleType.MOVIE)), REGION, null))
			.containsOnly(Map.entry(1L, new TmdbCacheService.TitleCacheIds(List.of(28), List.of())));
	}

	private Title title(Long id, String externalId, TitleType type) {
		return new Title(id, externalId, "TMDB", type, "Title " + id, null, null, null,
			Instant.EPOCH, Instant.EPOCH);
	}

	private TmdbTitleCache cacheRow(
			String tmdbId, String type, List<Integer> genreIds, Map<String, List<Integer>> watchProviders) {
		TmdbTitleCache row = new TmdbTitleCache();
		row.setTmdbId(tmdbId);
		row.setType(type);
		row.setGenreIds(genreIds);
		row.setWatchProviders(watchProviders);
		return row;
	}
}
