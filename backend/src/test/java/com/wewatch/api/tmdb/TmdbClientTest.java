package com.wewatch.api.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

	@Test
	void searchReturnsEmptyListWhenNoResults() {
		server.expect(requestTo(containsString("/3/search/multi")))
			.andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

		List<TitleSearchResponse> results = tmdbClient.search("xyznotafilm", null);

		assertThat(results).isEmpty();
	}

	@Test
	void searchThrowsTmdbApiExceptionOnServerError() {
		server.expect(requestTo(containsString("/3/search/movie")))
			.andRespond(withServerError());

		assertThatThrownBy(() -> tmdbClient.search("inception", TitleType.MOVIE))
			.isInstanceOf(TmdbApiException.class);
	}

}
