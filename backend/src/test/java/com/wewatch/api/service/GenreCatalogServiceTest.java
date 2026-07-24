package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.GenreResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbGenre;

@ExtendWith(MockitoExtension.class)
class GenreCatalogServiceTest {

	@Mock
	private TmdbClient tmdbClient;

	private GenreCatalogService service;

	@BeforeEach
	void setUp() {
		service = new GenreCatalogService(tmdbClient, 24);
	}

	@Test
	void mergesTheMovieAndTvCatalogsIntoOneMap() {
		// The id spaces overlap only where the names agree (18 Drama), and TV carries ids of its
		// own (10759) — so one flat map is unambiguous and callers need not know a title's medium.
		when(tmdbClient.getGenres(TitleType.MOVIE))
			.thenReturn(List.of(new TmdbGenre(18, "Drama"), new TmdbGenre(878, "Science Fiction")));
		when(tmdbClient.getGenres(TitleType.TV))
			.thenReturn(List.of(new TmdbGenre(18, "Drama"), new TmdbGenre(10759, "Action & Adventure")));

		Map<Integer, String> names = service.genreNames();

		assertThat(names).containsOnly(
			Map.entry(18, "Drama"),
			Map.entry(878, "Science Fiction"),
			Map.entry(10759, "Action & Adventure")
		);
	}

	@Test
	void cachesTheCatalogAcrossCalls() {
		when(tmdbClient.getGenres(TitleType.MOVIE)).thenReturn(List.of(new TmdbGenre(18, "Drama")));
		when(tmdbClient.getGenres(TitleType.TV)).thenReturn(List.of());

		service.genreNames();
		service.genreNames();
		service.genreNames();

		verify(tmdbClient, times(1)).getGenres(TitleType.MOVIE);
		verify(tmdbClient, times(1)).getGenres(TitleType.TV);
	}

	@Test
	void returnsAnEmptyMapWhenTmdbIsDown() {
		// Losing the names costs the stats page its genre bars; it must not cost it the numbers,
		// which come entirely from our own tables.
		when(tmdbClient.getGenres(TitleType.MOVIE)).thenThrow(new TmdbApiException("boom", null));

		assertThat(service.genreNames()).isEmpty();
	}

	@Test
	void retriesAfterAFailureRatherThanCachingTheOutage() {
		// Caffeine records nothing when the mapping function returns null, so a blip must not
		// leave the catalog empty for the whole TTL window.
		when(tmdbClient.getGenres(TitleType.MOVIE))
			.thenThrow(new TmdbApiException("boom", null))
			.thenReturn(List.of(new TmdbGenre(18, "Drama")));
		when(tmdbClient.getGenres(TitleType.TV)).thenReturn(List.of());

		assertThat(service.genreNames()).isEmpty();
		assertThat(service.genreNames()).containsExactly(Map.entry(18, "Drama"));
	}

	// ── per-type accessor (#381) ────────────────────────────────

	@Test
	void keepsTheTwoCatalogsApartPerType() {
		// The merged map cannot answer this: a picker offering both "Action" (movie 28) and
		// "Action & Adventure" (TV 10759) in one list gives the user no way to tell them apart.
		when(tmdbClient.getGenres(TitleType.MOVIE))
			.thenReturn(List.of(new TmdbGenre(28, "Action"), new TmdbGenre(18, "Drama")));
		when(tmdbClient.getGenres(TitleType.TV))
			.thenReturn(List.of(new TmdbGenre(10759, "Action & Adventure"), new TmdbGenre(18, "Drama")));

		assertThat(service.genresFor(TitleType.MOVIE))
			.containsExactly(new GenreResponse(28, "Action"), new GenreResponse(18, "Drama"));
		assertThat(service.genresFor(TitleType.TV))
			.containsExactly(new GenreResponse(10759, "Action & Adventure"), new GenreResponse(18, "Drama"));
	}

	@Test
	void sortsEachCatalogByName() {
		when(tmdbClient.getGenres(TitleType.MOVIE))
			.thenReturn(List.of(new TmdbGenre(878, "Science Fiction"), new TmdbGenre(28, "Action")));
		when(tmdbClient.getGenres(TitleType.TV)).thenReturn(List.of());

		assertThat(service.genresFor(TitleType.MOVIE))
			.extracting(GenreResponse::name)
			.containsExactly("Action", "Science Fiction");
	}

	@Test
	void bothViewsShareOneFetchPair() {
		// The guard against someone adding a second cache for the per-type lists: the merged map
		// and both per-type lists are three views of one TMDB fetch pair, not three fetches.
		when(tmdbClient.getGenres(TitleType.MOVIE)).thenReturn(List.of(new TmdbGenre(18, "Drama")));
		when(tmdbClient.getGenres(TitleType.TV)).thenReturn(List.of(new TmdbGenre(10759, "Action & Adventure")));

		service.genresFor(TitleType.MOVIE);
		service.genreNames();
		service.genresFor(TitleType.TV);
		service.genresFor(TitleType.MOVIE);

		verify(tmdbClient, times(1)).getGenres(TitleType.MOVIE);
		verify(tmdbClient, times(1)).getGenres(TitleType.TV);
	}

	@Test
	void returnsAnEmptyListPerTypeWhenTmdbIsDown() {
		// Same never-throws contract as genreNames(): a genre picker with no options is a poorer
		// page, not a 502.
		when(tmdbClient.getGenres(TitleType.MOVIE)).thenThrow(new TmdbApiException("boom", null));

		assertThat(service.genresFor(TitleType.MOVIE)).isEmpty();
		assertThat(service.genresFor(TitleType.TV)).isEmpty();
	}
}
