package com.wewatch.api.tmdb;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.stream.Collectors;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;

@Component
public class TmdbClient {

	private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500";
	private static final String STILL_BASE_URL = "https://image.tmdb.org/t/p/w300";

	private final RestClient restClient;

	public TmdbClient(RestClient.Builder builder, @Value("${tmdb.api-key}") String apiKey) {
		this.restClient = builder
			.baseUrl("https://api.themoviedb.org")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

	public List<TitleSearchResponse> search(String query, TitleType type) {
		try {
			if (type == TitleType.MOVIE) {
				return fetchItems("/3/search/movie", query).stream()
					.map(item -> toResponse(item, TitleType.MOVIE))
					.toList();
			} else if (type == TitleType.TV) {
				return fetchItems("/3/search/tv", query).stream()
					.map(item -> toResponse(item, TitleType.TV))
					.toList();
			} else {
				return fetchItems("/3/search/multi", query).stream()
					.filter(item -> "movie".equals(item.mediaType()) || "tv".equals(item.mediaType()))
					.map(item -> toResponse(item, "movie".equals(item.mediaType()) ? TitleType.MOVIE : TitleType.TV))
					.toList();
			}
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB search failed: " + e.getMessage(), e);
		}
	}

	public TmdbTvDetail getTvDetail(String tmdbId) {
		try {
			TmdbTvDetail detail = restClient.get()
				.uri("/3/tv/{id}?language=en-US", tmdbId)
				.retrieve()
				.body(TmdbTvDetail.class);
			return detail != null ? detail : new TmdbTvDetail(0L, 0, null, null, List.of(), null, null, null, List.of());
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get TV detail failed: " + e.getMessage(), e);
		}
	}

	public List<TmdbTvSeason> getSeasons(String tmdbId) {
		TmdbTvDetail detail = getTvDetail(tmdbId);
		return detail.seasons() != null ? detail.seasons() : List.of();
	}

	public TmdbTvSeason getSeasonDetail(String tmdbId, int seasonNumber) {
		try {
			TmdbTvSeason season = restClient.get()
				.uri("/3/tv/{id}/season/{season}?language=en-US", tmdbId, seasonNumber)
				.retrieve()
				.body(TmdbTvSeason.class);
			if (season == null) {
				throw new TmdbApiException("TMDB returned null for season " + seasonNumber, null);
			}
			return season;
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get season detail failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> getRecommendations(TitleType type, String tmdbId) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/{mediaType}/{id}/recommendations?language=en-US&page=1", mediaType, tmdbId)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB recommendations failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> getTrending(TitleType type) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/trending/{mediaType}/week?language=en-US", mediaType)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB trending failed: " + e.getMessage(), e);
		}
	}

	public TmdbMovieDetail getMovieDetail(String tmdbId) {
		try {
			TmdbMovieDetail detail = restClient.get()
				.uri("/3/movie/{id}?language=en-US", tmdbId)
				.retrieve()
				.body(TmdbMovieDetail.class);
			return detail != null ? detail : new TmdbMovieDetail(0L, null, null, null, null, null, List.of());
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get movie detail failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> getSimilar(TitleType type, String tmdbId) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/{mediaType}/{id}/similar?language=en-US&page=1", mediaType, tmdbId)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB similar failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> discover(TitleType type, List<Integer> genreIds, int voteCountGte) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		String genres = genreIds.stream().map(String::valueOf).collect(Collectors.joining(","));
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/discover/{mediaType}?with_genres={genres}&sort_by=popularity.desc&vote_count.gte={voteCount}&language=en-US",
					mediaType, genres, voteCountGte)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB discover failed: " + e.getMessage(), e);
		}
	}

	public static String posterUrl(String posterPath) {
		return posterPath != null ? POSTER_BASE_URL + posterPath : null;
	}

	public static String stillUrl(String stillPath) {
		return stillPath != null ? STILL_BASE_URL + stillPath : null;
	}

	private List<TmdbItem> fetchItems(String path, String query) {
		TmdbSearchPage page = restClient.get()
			.uri(path + "?query={q}&language=en-US&page=1", query)
			.retrieve()
			.body(TmdbSearchPage.class);
		return page != null && page.results() != null ? page.results() : List.of();
	}

	private TitleSearchResponse toResponse(TmdbItem item, TitleType type) {
		String name = type == TitleType.MOVIE ? item.title() : item.name();
		String dateStr = type == TitleType.MOVIE ? item.releaseDate() : item.firstAirDate();
		LocalDate releaseDate = (dateStr != null && !dateStr.isBlank()) ? LocalDate.parse(dateStr) : null;
		String posterUrl = item.posterPath() != null ? POSTER_BASE_URL + item.posterPath() : null;

		return new TitleSearchResponse(
			String.valueOf(item.id()),
			"TMDB",
			type,
			name,
			item.overview(),
			releaseDate,
			posterUrl
		);
	}

}
