package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.StatsResponse;
import com.wewatch.api.dto.StatsResponse.GenreStat;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.projection.WatchedEpisodeRuntime;
import com.wewatch.api.repository.projection.WatchlistTitle;

/**
 * The arithmetic is the feature, so it is what these tests pin down (#323).
 *
 * <p>Every repository is mocked and no test in this project executes SQL, which is precisely why
 * the summing, the movie/show split and the genre attribution all live in the service: here they
 * are reachable. The two native queries themselves are verified by hand against local Postgres.
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

	private static final Long WATCHLIST_ID = 10L;

	private static final int DRAMA = 18;
	private static final int SCI_FI = 878;
	private static final int COMEDY = 35;

	@Mock
	private TitleRepository titleRepository;

	@Mock
	private EpisodeProgressRepository episodeProgressRepository;

	@Mock
	private TmdbTitleCacheRepository titleCacheRepository;

	@Mock
	private GenreCatalogService genreCatalogService;

	private StatsService service;

	@BeforeEach
	void setUp() {
		service = new StatsService(
			titleRepository, episodeProgressRepository, titleCacheRepository, genreCatalogService);
		lenient().when(genreCatalogService.genreNames())
			.thenReturn(Map.of(DRAMA, "Drama", SCI_FI, "Science Fiction", COMEDY, "Comedy"));
	}

	// ─── Fixtures ────────────────────────────────────────────────────────────

	private static WatchlistTitle entry(String externalId, String type, String status) {
		WatchlistTitle t = mock(WatchlistTitle.class);
		lenient().when(t.getExternalId()).thenReturn(externalId);
		lenient().when(t.getType()).thenReturn(type);
		lenient().when(t.getStatus()).thenReturn(status);
		return t;
	}

	private static WatchedEpisodeRuntime watchedEpisode(String showId, Integer runtime) {
		WatchedEpisodeRuntime e = mock(WatchedEpisodeRuntime.class);
		lenient().when(e.getExternalId()).thenReturn(showId);
		lenient().when(e.getRuntimeMinutes()).thenReturn(runtime);
		return e;
	}

	// tmdbId here is the bare id ("m1", "tv1", ...); the row is stored under its medium-scoped
	// cache key (#394), matching how TmdbCacheService writes it — StatsService's batch read keys
	// on TmdbCacheKey.movie(id) for finished movies and TmdbCacheKey.tv(id) for watched shows.
	private static TmdbTitleCache cached(String tmdbId, TitleType type, Integer runtime, Integer... genreIds) {
		TmdbTitleCache c = new TmdbTitleCache();
		c.setTmdbId(TmdbCacheKey.of(type, tmdbId));
		c.setRuntimeMinutes(runtime);
		c.setGenreIds(List.of(genreIds));
		return c;
	}

	private void givenEntries(WatchlistTitle... entries) {
		when(titleRepository.findWatchlistTitles(WATCHLIST_ID)).thenReturn(List.of(entries));
	}

	private void givenWatchedEpisodes(WatchedEpisodeRuntime... episodes) {
		when(episodeProgressRepository.findWatchedEpisodeRuntimes(WATCHLIST_ID))
			.thenReturn(List.of(episodes));
	}

	private void givenCache(TmdbTitleCache... rows) {
		when(titleCacheRepository.findAllById(any())).thenReturn(List.of(rows));
	}

	// ─── Watch time ──────────────────────────────────────────────────────────

	@Test
	void sumsMovieRuntimesAndEpisodeRuntimesIntoTheTotal() {
		givenEntries(entry("m1", "MOVIE", "WATCHED"), entry("m2", "MOVIE", "WATCHED"));
		givenWatchedEpisodes(watchedEpisode("tv1", 50), watchedEpisode("tv1", 45));
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA), cached("m2", TitleType.MOVIE,96, COMEDY), cached("tv1", TitleType.TV,null, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.movieMinutes()).isEqualTo(216);
		assertThat(stats.episodeMinutes()).isEqualTo(95);
		assertThat(stats.totalMinutes()).isEqualTo(311);
	}

	@Test
	void countsAnEpisodeWithNoCachedRuntimeButAddsNoTime() {
		// The left join behind the query yields the row with a null runtime rather than dropping
		// it — the user watched the episode, we just don't know how long it was.
		givenEntries();
		givenWatchedEpisodes(
			watchedEpisode("tv1", 50),
			watchedEpisode("tv1", null),
			watchedEpisode("tv1", 0)
		);
		givenCache(cached("tv1", TitleType.TV,null, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.episodesFinished()).isEqualTo(3);
		assertThat(stats.episodeMinutes()).isEqualTo(50);
		// Both the null and the zero: TMDB uses them interchangeably for "unknown".
		assertThat(stats.itemsMissingRuntime()).isEqualTo(2);
	}

	@Test
	void countsAMovieWithNoCacheRowButAddsNoTime() {
		givenEntries(entry("m1", "MOVIE", "WATCHED"));
		givenWatchedEpisodes();
		givenCache(); // nothing cached for m1 at all

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.moviesFinished()).isEqualTo(1);
		assertThat(stats.movieMinutes()).isZero();
		assertThat(stats.itemsMissingRuntime()).isEqualTo(1);
		assertThat(stats.genres()).isEmpty();
	}

	// ─── Counts ──────────────────────────────────────────────────────────────

	@Test
	void countsOnlyFinishedTitlesTowardMoviesAndShows() {
		givenEntries(
			entry("m1", "MOVIE", "WATCHED"),
			entry("m2", "MOVIE", "WANT_TO_WATCH"),
			entry("tv1", "TV", "WATCHED"),
			entry("tv2", "TV", "WATCHING")
		);
		givenWatchedEpisodes();
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.moviesFinished()).isEqualTo(1);
		assertThat(stats.showsFinished()).isEqualTo(1);
	}

	@Test
	void countsEpisodesOfAnUnfinishedShow() {
		// 40 episodes into a show you are still WATCHING is 40 episodes watched. Episode counts
		// are independent of entry status; only movies/shows *finished* look at status.
		givenEntries(entry("tv1", "TV", "WATCHING"));
		givenWatchedEpisodes(watchedEpisode("tv1", 50), watchedEpisode("tv1", 50));
		givenCache(cached("tv1", TitleType.TV,null, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.showsFinished()).isZero();
		assertThat(stats.episodesFinished()).isEqualTo(2);
		assertThat(stats.episodeMinutes()).isEqualTo(100);
		assertThat(stats.genres()).extracting(GenreStat::name).containsExactly("Drama");
	}

	@Test
	void countsAShowMarkedFinishedWithoutTickingItsEpisodes() {
		// Flipping a show straight to WATCHED writes no episode_progress rows, so there is no
		// runtime to sum. It is still a show you finished.
		givenEntries(entry("tv1", "TV", "WATCHED"));
		givenWatchedEpisodes();
		givenCache();

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.showsFinished()).isEqualTo(1);
		assertThat(stats.episodesFinished()).isZero();
		assertThat(stats.totalMinutes()).isZero();
		// Nothing was watched at episode level, so nothing is claimed as missing runtime either.
		assertThat(stats.itemsMissingRuntime()).isZero();
	}

	@Test
	void countsATitleOnceEvenIfItWasAddedTwice() {
		givenEntries(entry("m1", "MOVIE", "WATCHED"), entry("m1", "MOVIE", "WATCHED"));
		givenWatchedEpisodes();
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.moviesFinished()).isEqualTo(1);
		assertThat(stats.movieMinutes()).isEqualTo(120);
	}

	// ─── Genre breakdown ─────────────────────────────────────────────────────

	@Test
	void attributesATitlesFullTimeToEachOfItsGenres() {
		// A shape, not a partition: the parts deliberately exceed the total, which is why the
		// response carries minutes rather than percentages.
		givenEntries(entry("m1", "MOVIE", "WATCHED"));
		givenWatchedEpisodes();
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA, SCI_FI));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.totalMinutes()).isEqualTo(120);
		assertThat(stats.genres()).containsExactly(
			new GenreStat(DRAMA, "Drama", 120, 1),
			new GenreStat(SCI_FI, "Science Fiction", 120, 1)
		);
	}

	@Test
	void ranksGenresByWatchTimeDescending() {
		givenEntries(entry("m1", "MOVIE", "WATCHED"), entry("m2", "MOVIE", "WATCHED"));
		givenWatchedEpisodes(watchedEpisode("tv1", 300));
		givenCache(
			cached("m1", TitleType.MOVIE,90, COMEDY),
			cached("m2", TitleType.MOVIE,120, COMEDY),
			cached("tv1", TitleType.TV,null, SCI_FI)
		);

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.genres()).containsExactly(
			new GenreStat(SCI_FI, "Science Fiction", 300, 1),
			new GenreStat(COMEDY, "Comedy", 210, 2)
		);
	}

	@Test
	void rollsAShowsEpisodesUpIntoOneTitleForItsGenres() {
		// Three episodes of one show is one title in the breakdown, not three.
		givenEntries(entry("tv1", "TV", "WATCHING"));
		givenWatchedEpisodes(
			watchedEpisode("tv1", 50),
			watchedEpisode("tv1", 50),
			watchedEpisode("tv1", 50)
		);
		givenCache(cached("tv1", TitleType.TV,null, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.genres()).containsExactly(new GenreStat(DRAMA, "Drama", 150, 1));
	}

	@Test
	void skipsGenreIdsTheCatalogCannotName() {
		// A retired TMDB genre, or the catalog being unavailable — better no row than a bar
		// labelled "10402".
		int unknown = 10402;
		givenEntries(entry("m1", "MOVIE", "WATCHED"));
		givenWatchedEpisodes();
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA, unknown));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.genres()).containsExactly(new GenreStat(DRAMA, "Drama", 120, 1));
	}

	@Test
	void rendersWithoutGenresWhenTheCatalogIsUnavailable() {
		// GenreCatalogService swallows a TMDB outage into an empty map. The numbers still come
		// entirely from our own tables, so they must survive it.
		when(genreCatalogService.genreNames()).thenReturn(Map.of());
		givenEntries(entry("m1", "MOVIE", "WATCHED"));
		givenWatchedEpisodes();
		givenCache(cached("m1", TitleType.MOVIE,120, DRAMA));

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.totalMinutes()).isEqualTo(120);
		assertThat(stats.moviesFinished()).isEqualTo(1);
		assertThat(stats.genres()).isEmpty();
	}

	// ─── Empty ───────────────────────────────────────────────────────────────

	@Test
	void returnsZeroesForAnEmptyWatchlist() {
		givenEntries();
		givenWatchedEpisodes();
		givenCache();

		StatsResponse stats = service.statsFor(WATCHLIST_ID);

		assertThat(stats.moviesFinished()).isZero();
		assertThat(stats.showsFinished()).isZero();
		assertThat(stats.episodesFinished()).isZero();
		assertThat(stats.totalMinutes()).isZero();
		assertThat(stats.movieMinutes()).isZero();
		assertThat(stats.episodeMinutes()).isZero();
		assertThat(stats.itemsMissingRuntime()).isZero();
		assertThat(stats.genres()).isEmpty();
	}
}
