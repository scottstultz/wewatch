package com.wewatch.api.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbTvSeason;

@RestClientTest(TmdbClient.class)
@TestPropertySource(properties = "tmdb.api-key=test-key")
class TmdbClientTest {

	@Autowired
	private TmdbClient tmdbClient;

	@Autowired
	private MockRestServiceServer server;

	private static final String MOVIE_JSON = """
		{
		  "results": [
		    {
		      "id": 27205,
		      "title": "Inception",
		      "overview": "A thief who steals corporate secrets through dream-sharing technology.",
		      "release_date": "2010-07-16",
		      "poster_path": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg"
		    }
		  ]
		}
		""";

	private static final String TV_JSON = """
		{
		  "results": [
		    {
		      "id": 1396,
		      "name": "Breaking Bad",
		      "overview": "A chemistry teacher diagnosed with cancer turns to manufacturing drugs.",
		      "first_air_date": "2008-01-20",
		      "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"
		    }
		  ]
		}
		""";

	private static final String MULTI_JSON = """
		{
		  "results": [
		    {
		      "id": 27205,
		      "media_type": "movie",
		      "title": "Inception",
		      "overview": "A thief who steals corporate secrets.",
		      "release_date": "2010-07-16",
		      "poster_path": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg"
		    },
		    {
		      "id": 1396,
		      "media_type": "tv",
		      "name": "Breaking Bad",
		      "overview": "A chemistry teacher turns to crime.",
		      "first_air_date": "2008-01-20",
		      "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"
		    },
		    {
		      "id": 12345,
		      "media_type": "person",
		      "name": "Leonardo DiCaprio"
		    }
		  ]
		}
		""";

	@Test
	void searchMoviesReturnsMappedMovieResults() {
		server.expect(requestTo(containsString("/3/search/movie")))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("inception", TitleType.MOVIE);

		assertThat(results).hasSize(1);
		TitleSearchResponse result = results.get(0);
		assertThat(result.externalId()).isEqualTo("27205");
		assertThat(result.externalSource()).isEqualTo("TMDB");
		assertThat(result.type()).isEqualTo(TitleType.MOVIE);
		assertThat(result.name()).isEqualTo("Inception");
		assertThat(result.releaseDate().toString()).isEqualTo("2010-07-16");
		assertThat(result.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg");
	}

	@Test
	void searchTvReturnsMappedTvResults() {
		server.expect(requestTo(containsString("/3/search/tv")))
			.andRespond(withSuccess(TV_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("breaking bad", TitleType.TV);

		assertThat(results).hasSize(1);
		TitleSearchResponse result = results.get(0);
		assertThat(result.externalId()).isEqualTo("1396");
		assertThat(result.type()).isEqualTo(TitleType.TV);
		assertThat(result.name()).isEqualTo("Breaking Bad");
		assertThat(result.releaseDate().toString()).isEqualTo("2008-01-20");
		assertThat(result.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg");
	}

	@Test
	void searchMultiReturnsBothTypesAndFiltersPersonResults() {
		server.expect(requestTo(containsString("/3/search/multi")))
			.andRespond(withSuccess(MULTI_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("inception", null);

		assertThat(results).hasSize(2);
		assertThat(results.stream().map(TitleSearchResponse::type))
			.containsExactly(TitleType.MOVIE, TitleType.TV);
		assertThat(results.stream().map(TitleSearchResponse::name))
			.containsExactly("Inception", "Breaking Bad");
	}

	private static final String MALFORMED_DATE_JSON = """
		{
		  "results": [
		    {
		      "id": 27205,
		      "title": "Inception",
		      "overview": "A thief who steals corporate secrets.",
		      "release_date": "2010-07-16",
		      "poster_path": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg"
		    },
		    {
		      "id": 99999,
		      "title": "Obscure Film",
		      "overview": "A film with bad catalog data.",
		      "release_date": "2010-7-16",
		      "poster_path": null
		    }
		  ]
		}
		""";

	@Test
	void searchDegradesMalformedDateToNullWithoutFailingOtherResults() {
		server.expect(requestTo(containsString("/3/search/movie")))
			.andRespond(withSuccess(MALFORMED_DATE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("inception", TitleType.MOVIE);

		assertThat(results).hasSize(2);
		assertThat(results.get(0).releaseDate().toString()).isEqualTo("2010-07-16");
		assertThat(results.get(1).name()).isEqualTo("Obscure Film");
		assertThat(results.get(1).releaseDate()).isNull();
	}

	@Test
	void searchReturnsEmptyListWhenNoResults() {
		server.expect(requestTo(containsString("/3/search/multi")))
			.andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("xyznotafilm", null);

		assertThat(results).isEmpty();
	}

	@Test
	void searchThrowsTmdbApiExceptionOnReadTimeout() {
		server.expect(requestTo(containsString("/3/search/movie")))
			.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> tmdbClient.search("inception", TitleType.MOVIE))
			.isInstanceOf(TmdbApiException.class)
			.hasRootCauseInstanceOf(SocketTimeoutException.class);
	}

	@Test
	void searchThrowsTmdbApiExceptionOnServerError() {
		server.expect(requestTo(containsString("/3/search/movie")))
			.andRespond(withServerError());

		assertThatThrownBy(() -> tmdbClient.search("inception", TitleType.MOVIE))
			.isInstanceOf(TmdbApiException.class);
	}

	// ─── discover / trending ─────────────────────────────────────────────────

	@Test
	void discoverAppliesSortVoteFloorAndMovieReleaseWindow() {
		server.expect(requestTo(allOf(
			containsString("/3/discover/movie"),
			containsString("sort_by=vote_average.desc"),
			containsString("vote_count.gte=200"),
			containsString("primary_release_date.gte=2026-05-04"),
			containsString("primary_release_date.lte=2026-07-03"))))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discover(TitleType.MOVIE, List.of(28), List.of(), 200,
			"vote_average.desc", LocalDate.of(2026, 5, 4), LocalDate.of(2026, 7, 3), null, null, 1);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).name()).isEqualTo("Inception");
	}

	@Test
	void discoverUsesFirstAirDateWindowForTv() {
		server.expect(requestTo(allOf(
			containsString("/3/discover/tv"),
			containsString("first_air_date.gte=2026-05-04"))))
			.andRespond(withSuccess(TV_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discover(TitleType.TV, List.of(18), List.of(), 20,
			"popularity.desc", LocalDate.of(2026, 5, 4), null, null, null, 1);

		assertThat(results).hasSize(1);
	}

	@Test
	void discoverByPersonQueriesMovieDiscoverWithPeople() {
		// Person shelves are movie-only (#269): TMDB's TV discover has no people filter
		server.expect(requestTo(allOf(
			containsString("/3/discover/movie"),
			containsString("with_people=6384"),
			containsString("vote_count.gte=50"),
			containsString("page=1"))))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discoverByPerson(6384, 50, 1);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).type()).isEqualTo(TitleType.MOVIE);
		assertThat(results.get(0).name()).isEqualTo("Inception");
	}

	@Test
	void discoverByKeywordQueriesTheDominantMediaType() {
		// Keyword shelves follow the list's dominant type (#271): with_keywords
		// works on TV discover, unlike with_people
		server.expect(requestTo(allOf(
			containsString("/3/discover/tv"),
			containsString("with_keywords=9882"),
			containsString("vote_count.gte=100"),
			containsString("page=2"))))
			.andRespond(withSuccess(TV_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discoverByKeyword(TitleType.TV, 9882, 100, null, null, 2);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).type()).isEqualTo(TitleType.TV);
		assertThat(results.get(0).name()).isEqualTo("Breaking Bad");
	}

	@Test
	void discoverByKeywordAppliesTheWatchProviderFilter() {
		server.expect(requestTo(allOf(
			containsString("/3/discover/movie"),
			containsString("with_keywords=9882"),
			containsString("watch_region=US"),
			containsString("with_watch_providers=8"),
			containsString("with_watch_monetization_types=flatrate"))))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discoverByKeyword(TitleType.MOVIE, 9882, 100, "US", List.of(8), 1);

		assertThat(results).hasSize(1);
	}

	@Test
	void getKeywordsParsesNamesFromBothResponseShapes() {
		// Movie responses nest under "keywords", TV under "results"; both carry
		// the names that label keyword shelves (#271)
		server.expect(requestTo(containsString("/3/movie/27205/keywords")))
			.andRespond(withSuccess("""
				{ "keywords": [ { "id": 9882, "name": "space race" } ] }
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(containsString("/3/tv/1396/keywords")))
			.andRespond(withSuccess("""
				{ "results": [ { "id": 10391, "name": "drug cartel" } ] }
				""", MediaType.APPLICATION_JSON));

		assertThat(tmdbClient.getKeywords(TitleType.MOVIE, "27205"))
			.containsExactly(new TmdbKeyword(9882, "space race"));
		assertThat(tmdbClient.getKeywords(TitleType.TV, "1396"))
			.containsExactly(new TmdbKeyword(10391, "drug cartel"));
	}

	@Test
	void trendingFetchesTheRequestedPage() {
		server.expect(requestTo(allOf(
			containsString("/3/trending/tv/week"),
			containsString("page=2"))))
			.andRespond(withSuccess(TV_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.getTrending(TitleType.TV, 2);

		assertThat(results).hasSize(1);
	}

	// ─── getTvDetail ─────────────────────────────────────────────────────────

	private static final String TV_DETAIL_JSON = """
		{
		  "id": 1399,
		  "number_of_seasons": 8,
		  "seasons": [
		    {
		      "id": 3624,
		      "season_number": 0,
		      "name": "Specials",
		      "episode_count": 53,
		      "poster_path": "/specials.jpg",
		      "air_date": "2010-12-05"
		    },
		    {
		      "id": 3625,
		      "season_number": 1,
		      "name": "Season 1",
		      "episode_count": 10,
		      "poster_path": "/season1.jpg",
		      "air_date": "2011-04-17"
		    }
		  ],
		  "credits": {
		    "cast": [
		      { "id": 1223786, "name": "Emilia Clarke", "character": "Daenerys Targaryen", "profile_path": "/emilia.jpg", "order": 0 },
		      { "id": 12795, "name": "Kit Harington", "character": "Jon Snow", "order": 1 }
		    ],
		    "crew": [
		      { "id": 44797, "name": "Miguel Sapochnik", "job": "Director" },
		      { "id": 9813, "name": "David Benioff", "job": "Executive Producer" }
		    ]
		  }
		}
		""";

	@Test
	void getTvDetailReturnsMappedSeasonList() {
		server.expect(requestTo(containsString("/3/tv/1399")))
			.andRespond(withSuccess(TV_DETAIL_JSON, MediaType.APPLICATION_JSON));

		List<TmdbTvSeason> seasons = tmdbClient.getTvDetail("1399").seasons();

		assertThat(seasons).hasSize(2);
		assertThat(seasons.get(0).seasonNumber()).isEqualTo(0);
		assertThat(seasons.get(0).name()).isEqualTo("Specials");
		assertThat(seasons.get(0).episodeCount()).isEqualTo(53);
		assertThat(seasons.get(1).seasonNumber()).isEqualTo(1);
		assertThat(seasons.get(1).name()).isEqualTo("Season 1");
		assertThat(seasons.get(1).episodeCount()).isEqualTo(10);
		assertThat(seasons.get(1).posterPath()).isEqualTo("/season1.jpg");
	}

	@Test
	void getTvDetailRequestsAndParsesAppendedCredits() {
		// Credits ride the detail call via append_to_response (#269) — no extra request
		server.expect(requestTo(allOf(
			containsString("/3/tv/1399"),
			containsString("append_to_response=credits"))))
			.andRespond(withSuccess(TV_DETAIL_JSON, MediaType.APPLICATION_JSON));

		TmdbCredits credits = tmdbClient.getTvDetail("1399").credits();

		assertThat(credits.cast())
			.containsExactly(
				new TmdbCastMember(1223786L, "Emilia Clarke", "Daenerys Targaryen", "/emilia.jpg", 0),
				// no profile_path — TMDB has no headshot for some credits (#295)
				new TmdbCastMember(12795L, "Kit Harington", "Jon Snow", null, 1));
		assertThat(credits.crew())
			.containsExactly(
				new TmdbCrewMember(44797L, "Miguel Sapochnik", "Director"),
				new TmdbCrewMember(9813L, "David Benioff", "Executive Producer"));
	}

	@Test
	void getMovieDetailRequestsAndParsesAppendedCredits() {
		server.expect(requestTo(allOf(
			containsString("/3/movie/27205"),
			containsString("append_to_response=credits"))))
			.andRespond(withSuccess("""
				{
				  "id": 27205,
				  "title": "Inception",
				  "credits": {
				    "cast": [
				      { "id": 6193, "name": "Leonardo DiCaprio", "order": 0 }
				    ],
				    "crew": [
				      { "id": 525, "name": "Christopher Nolan", "job": "Director" }
				    ]
				  }
				}
				""", MediaType.APPLICATION_JSON));

		TmdbCredits credits = tmdbClient.getMovieDetail("27205").credits();

		assertThat(credits.cast()).containsExactly(new TmdbCastMember(6193L, "Leonardo DiCaprio", null, null, 0));
		assertThat(credits.crew()).containsExactly(new TmdbCrewMember(525L, "Christopher Nolan", "Director"));
	}

	// ─── watch providers (#270) ──────────────────────────────────────────────

	@Test
	void discoverAppliesWatchProviderFilterWithRegionAndFlatrate() {
		server.expect(requestTo(allOf(
			containsString("/3/discover/movie"),
			containsString("watch_region=US"),
			containsString("with_watch_providers=8"),
			containsString("with_watch_monetization_types=flatrate"))))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discover(TitleType.MOVIE, List.of(28), List.of(), 200,
			"popularity.desc", null, null, "US", List.of(8), 1);

		assertThat(results).hasSize(1);
	}

	@Test
	void discoverSkipsProviderFilterWithoutARegion() {
		server.expect(requestTo(org.hamcrest.Matchers.not(containsString("watch_region"))))
			.andRespond(withSuccess(MOVIE_JSON, MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.discover(TitleType.MOVIE, List.of(28), List.of(), 200,
			"popularity.desc", null, null, null, null, 1);

		assertThat(results).hasSize(1);
	}

	@Test
	void getMovieDetailRequestsAndParsesAppendedWatchProviders() {
		server.expect(requestTo(allOf(
			containsString("/3/movie/27205"),
			containsString("append_to_response=credits,watch/providers"))))
			.andRespond(withSuccess("""
				{
				  "id": 27205,
				  "title": "Inception",
				  "watch/providers": {
				    "results": {
				      "US": {
				        "flatrate": [
				          { "provider_id": 8, "provider_name": "Netflix", "logo_path": "/n.jpg", "display_priority": 0 }
				        ]
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		TmdbWatchProviders providers = tmdbClient.getMovieDetail("27205").watchProviders();

		assertThat(providers.results().get("US").flatrate())
			.containsExactly(new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0));
	}

	@Test
	void getWatchProvidersListsRegionCatalog() {
		server.expect(requestTo(allOf(
			containsString("/3/watch/providers/tv"),
			containsString("watch_region=US"))))
			.andRespond(withSuccess("""
				{
				  "results": [
				    { "provider_id": 8, "provider_name": "Netflix", "logo_path": "/n.jpg", "display_priority": 0 },
				    { "provider_id": 9, "provider_name": "Prime Video", "logo_path": "/p.jpg", "display_priority": 1 }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		List<TmdbWatchProvider> providers = tmdbClient.getWatchProviders(TitleType.TV, "US");

		assertThat(providers).hasSize(2);
		assertThat(providers.get(0).providerName()).isEqualTo("Netflix");
	}

	@Test
	void getWatchRegionsListsRegions() {
		server.expect(requestTo(containsString("/3/watch/providers/regions")))
			.andRespond(withSuccess("""
				{
				  "results": [
				    { "iso_3166_1": "US", "english_name": "United States", "native_name": "United States" }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		List<TmdbWatchRegion> regions = tmdbClient.getWatchRegions();

		assertThat(regions).containsExactly(new TmdbWatchRegion("US", "United States"));
	}

	// ─── getSeasonDetail ─────────────────────────────────────────────────────

	private static final String SEASON_DETAIL_JSON = """
		{
		  "id": 3625,
		  "season_number": 1,
		  "name": "Season 1",
		  "overview": "The first season.",
		  "poster_path": "/season1.jpg",
		  "air_date": "2011-04-17",
		  "episodes": [
		    {
		      "id": 63056,
		      "episode_number": 1,
		      "name": "Winter Is Coming",
		      "overview": "Jon Arryn has died.",
		      "air_date": "2011-04-17",
		      "still_path": "/ep1.jpg",
		      "runtime": 62
		    },
		    {
		      "id": 63057,
		      "episode_number": 2,
		      "name": "The Kingsroad",
		      "overview": "The Stark family deals with bitter truths.",
		      "air_date": "2011-04-24",
		      "still_path": "/ep2.jpg",
		      "runtime": 56
		    },
		    {
		      "id": 63058,
		      "episode_number": 3,
		      "name": "Lord Snow",
		      "overview": "Jon Snow arrives at the Wall.",
		      "air_date": "2011-05-01",
		      "still_path": null,
		      "runtime": 58
		    }
		  ]
		}
		""";

	@Test
	void getSeasonDetailReturnsMappedEpisodeList() {
		server.expect(requestTo(containsString("/3/tv/1399/season/1")))
			.andRespond(withSuccess(SEASON_DETAIL_JSON, MediaType.APPLICATION_JSON));

		TmdbTvSeason season = tmdbClient.getSeasonDetail("1399", 1);

		assertThat(season.seasonNumber()).isEqualTo(1);
		assertThat(season.name()).isEqualTo("Season 1");
		assertThat(season.episodes()).hasSize(3);
		assertThat(season.episodes().get(0).episodeNumber()).isEqualTo(1);
		assertThat(season.episodes().get(0).name()).isEqualTo("Winter Is Coming");
		assertThat(season.episodes().get(0).airDate()).isEqualTo("2011-04-17");
		assertThat(season.episodes().get(0).stillPath()).isEqualTo("/ep1.jpg");
		assertThat(season.episodes().get(0).runtime()).isEqualTo(62);
		assertThat(season.episodes().get(2).stillPath()).isNull();
	}

	private static final String PERSON_DETAIL_JSON = """
		{
		  "id": 6384,
		  "name": "Keanu Reeves",
		  "biography": "Keanu Charles Reeves is a Canadian actor.",
		  "profile_path": "/keanu.jpg",
		  "known_for_department": "Acting",
		  "birthday": "1964-09-02",
		  "place_of_birth": "Beirut, Lebanon",
		  "combined_credits": {
		    "cast": [
		      {
		        "id": 603,
		        "media_type": "movie",
		        "title": "The Matrix",
		        "overview": "A computer hacker learns the true nature of reality.",
		        "release_date": "1999-03-31",
		        "poster_path": "/matrix.jpg",
		        "genre_ids": [28, 878],
		        "popularity": 92.5,
		        "vote_count": 24000,
		        "character": "Thomas A. Anderson"
		      },
		      {
		        "id": 1408,
		        "media_type": "tv",
		        "name": "Swedish Dicks",
		        "overview": "Two men run a private investigation firm.",
		        "first_air_date": "2016-09-15",
		        "poster_path": "/dicks.jpg",
		        "genre_ids": [35],
		        "popularity": 8.1,
		        "vote_count": 40,
		        "character": "Tex"
		      }
		    ],
		    "crew": []
		  }
		}
		""";

	@Test
	void getPersonDetailRequestsAndParsesCombinedCredits() {
		server.expect(requestTo(allOf(
				containsString("/3/person/6384"),
				containsString("append_to_response=combined_credits"))))
			.andRespond(withSuccess(PERSON_DETAIL_JSON, MediaType.APPLICATION_JSON));

		TmdbPersonDetail detail = tmdbClient.getPersonDetail(6384);

		assertThat(detail.id()).isEqualTo(6384L);
		assertThat(detail.name()).isEqualTo("Keanu Reeves");
		assertThat(detail.biography()).startsWith("Keanu Charles Reeves");
		assertThat(detail.profilePath()).isEqualTo("/keanu.jpg");
		assertThat(detail.knownForDepartment()).isEqualTo("Acting");
		assertThat(detail.birthday()).isEqualTo("1964-09-02");
		assertThat(detail.placeOfBirth()).isEqualTo("Beirut, Lebanon");

		List<TmdbPersonCredit> cast = detail.combinedCredits().cast();
		assertThat(cast).hasSize(2);
		assertThat(cast.get(0).mediaType()).isEqualTo("movie");
		assertThat(cast.get(0).title()).isEqualTo("The Matrix");
		assertThat(cast.get(0).popularity()).isEqualTo(92.5);
		assertThat(cast.get(0).voteCount()).isEqualTo(24000);
		assertThat(cast.get(0).character()).isEqualTo("Thomas A. Anderson");
		assertThat(cast.get(1).mediaType()).isEqualTo("tv");
		assertThat(cast.get(1).name()).isEqualTo("Swedish Dicks");
		assertThat(cast.get(1).firstAirDate()).isEqualTo("2016-09-15");
	}

	@Test
	void getPersonDetailWrapsServerErrors() {
		server.expect(requestTo(containsString("/3/person/6384")))
			.andRespond(withServerError());

		assertThatThrownBy(() -> tmdbClient.getPersonDetail(6384))
			.isInstanceOf(TmdbApiException.class)
			.hasMessageContaining("TMDB get person detail failed");
	}

}
