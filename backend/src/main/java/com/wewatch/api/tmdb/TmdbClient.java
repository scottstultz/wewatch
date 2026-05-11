package com.wewatch.api.tmdb;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;

@Component
public class TmdbClient {

	private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500";

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
