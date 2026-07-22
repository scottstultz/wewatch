package com.wewatch.api.tmdb;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.stream.Collectors;

import com.wewatch.api.dto.PersonSearchResult;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.dto.TitleSearchResults;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;

@Component
public class TmdbClient {

	private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500";
	private static final String STILL_BASE_URL = "https://image.tmdb.org/t/p/w300";
	private static final String PROVIDER_LOGO_BASE_URL = "https://image.tmdb.org/t/p/w92";
	private static final String PROFILE_BASE_URL = "https://image.tmdb.org/t/p/w185";

	private final RestClient restClient;

	public TmdbClient(RestClient.Builder builder, @Value("${tmdb.api-key}") String apiKey) {
		this.restClient = builder
			.baseUrl("https://api.themoviedb.org")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

	public TitleSearchResults search(String query, TitleType type) {
		try {
			if (type == TitleType.MOVIE) {
				List<TitleSearchResponse> titles = fetchItems("/3/search/movie", query).stream()
					.map(item -> toResponse(item, TitleType.MOVIE))
					.toList();
				return new TitleSearchResults(titles, List.of());
			} else if (type == TitleType.TV) {
				List<TitleSearchResponse> titles = fetchItems("/3/search/tv", query).stream()
					.map(item -> toResponse(item, TitleType.TV))
					.toList();
				return new TitleSearchResults(titles, List.of());
			} else {
				List<TmdbItem> items = fetchItems("/3/search/multi", query);
				List<TitleSearchResponse> titles = items.stream()
					.filter(item -> "movie".equals(item.mediaType()) || "tv".equals(item.mediaType()))
					.map(item -> toResponse(item, "movie".equals(item.mediaType()) ? TitleType.MOVIE : TitleType.TV))
					.toList();
				// People ride the same multi response (#356) — surfaced as a slim
				// row, not interleaved, ranked by TMDB popularity.
				List<PersonSearchResult> people = items.stream()
					.filter(item -> "person".equals(item.mediaType()))
					.sorted(Comparator.comparingDouble(TmdbItem::popularity).reversed())
					.map(item -> new PersonSearchResult(item.id(), item.name(), profileUrl(item.profilePath())))
					.toList();
				return new TitleSearchResults(titles, people);
			}
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB search failed: " + e.getMessage(), e);
		}
	}

	public TmdbTvDetail getTvDetail(String tmdbId) {
		try {
			// Credits (#269), watch providers (#270) and videos (#340) ride along
			// for free — one call still, bigger payload
			TmdbTvDetail detail = restClient.get()
				.uri("/3/tv/{id}?language=en-US&append_to_response=credits,watch/providers,videos", tmdbId)
				.retrieve()
				.body(TmdbTvDetail.class);
			return detail != null ? detail : new TmdbTvDetail(0L, 0, null, null, List.of(), null, null, null, List.of(), null, null, null, null, null);
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get TV detail failed: " + e.getMessage(), e);
		}
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

	public List<TitleSearchResponse> getRecommendations(TitleType type, String tmdbId, int pageNumber) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/{mediaType}/{id}/recommendations?language=en-US&page={page}", mediaType, tmdbId, pageNumber)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB recommendations failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> getTrending(TitleType type, int pageNumber) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/trending/{mediaType}/week?language=en-US&page={page}", mediaType, pageNumber)
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
			// Credits (#269), watch providers (#270) and videos (#340) ride along
			// for free — one call still, bigger payload
			TmdbMovieDetail detail = restClient.get()
				.uri("/3/movie/{id}?language=en-US&append_to_response=credits,watch/providers,videos", tmdbId)
				.retrieve()
				.body(TmdbMovieDetail.class);
			return detail != null ? detail : new TmdbMovieDetail(0L, null, null, null, null, null, List.of(), null, null, null, null, null, null, null);
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get movie detail failed: " + e.getMessage(), e);
		}
	}

	public List<TitleSearchResponse> getSimilar(TitleType type, String tmdbId, int pageNumber) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/{mediaType}/{id}/similar?language=en-US&page={page}", mediaType, tmdbId, pageNumber)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB similar failed: " + e.getMessage(), e);
		}
	}

	// sortBy is a TMDB discover sort key (e.g. popularity.desc, vote_average.desc).
	// The optional release window filters on primary_release_date for movies and
	// first_air_date for TV — TMDB uses different field names per media type.
	// With a watch region and provider ids (#270), results are restricted to
	// titles streamable (flatrate) on any of those services in that region.
	public List<TitleSearchResponse> discover(TitleType type, List<Integer> genreIds, List<Integer> keywordIds,
			int voteCountGte, String sortBy, LocalDate releasedAfter, LocalDate releasedBefore,
			String watchRegion, List<Integer> watchProviderIds, int pageNumber) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		// Use OR (|) so results match any of the user's top genres/keywords rather than requiring all
		String genres = genreIds.stream().map(String::valueOf).collect(Collectors.joining("|"));
		String uriStr = "/3/discover/{mediaType}?with_genres={genres}&sort_by={sortBy}&vote_count.gte={voteCount}&language=en-US&page={page}";
		if (!keywordIds.isEmpty()) {
			String keywords = keywordIds.stream().map(String::valueOf).collect(Collectors.joining("|"));
			uriStr += "&with_keywords=" + keywords;
		}
		String dateField = type == TitleType.MOVIE ? "primary_release_date" : "first_air_date";
		if (releasedAfter != null) {
			uriStr += "&" + dateField + ".gte=" + releasedAfter;
		}
		if (releasedBefore != null) {
			uriStr += "&" + dateField + ".lte=" + releasedBefore;
		}
		if (watchRegion != null && watchProviderIds != null && !watchProviderIds.isEmpty()) {
			String providers = watchProviderIds.stream().map(String::valueOf).collect(Collectors.joining("|"));
			// monetization_types=flatrate: without it, with_watch_providers also
			// matches rent/buy listings on the same provider
			uriStr += "&watch_region=" + watchRegion
				+ "&with_watch_providers=" + providers
				+ "&with_watch_monetization_types=flatrate";
		}
		try {
			TmdbSearchPage page = restClient.get()
				.uri(uriStr, mediaType, genres, sortBy, voteCountGte, pageNumber)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB discover failed: " + e.getMessage(), e);
		}
	}

	// Person-seeded shelves are movie-only (#269): TMDB's /discover/tv has no
	// people filter. with_people matches cast and crew alike, so one parameter
	// covers both "More with <actor>" and "Directed by <director>" shelves.
	public List<TitleSearchResponse> discoverByPerson(int personId, int voteCountGte, int pageNumber) {
		try {
			TmdbSearchPage page = restClient.get()
				.uri("/3/discover/movie?with_people={personId}&sort_by=popularity.desc&vote_count.gte={voteCount}&language=en-US&page={page}",
					personId, voteCountGte, pageNumber)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, TitleType.MOVIE)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB person discover failed: " + e.getMessage(), e);
		}
	}

	// Keyword-seeded shelves (#271): unlike with_people, with_keywords works for
	// both media types, so the shelf follows the list's dominant type. Takes the
	// same optional provider filter as the generic discover — it's a
	// discover-backed feed, so provider-filtering conventions (#270) apply.
	public List<TitleSearchResponse> discoverByKeyword(TitleType type, int keywordId, int voteCountGte,
			String watchRegion, List<Integer> watchProviderIds, int pageNumber) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		String uriStr = "/3/discover/{mediaType}?with_keywords={keywordId}&sort_by=popularity.desc&vote_count.gte={voteCount}&language=en-US&page={page}";
		if (watchRegion != null && watchProviderIds != null && !watchProviderIds.isEmpty()) {
			String providers = watchProviderIds.stream().map(String::valueOf).collect(Collectors.joining("|"));
			uriStr += "&watch_region=" + watchRegion
				+ "&with_watch_providers=" + providers
				+ "&with_watch_monetization_types=flatrate";
		}
		try {
			TmdbSearchPage page = restClient.get()
				.uri(uriStr, mediaType, keywordId, voteCountGte, pageNumber)
				.retrieve()
				.body(TmdbSearchPage.class);
			List<TmdbItem> items = page != null && page.results() != null ? page.results() : List.of();
			return items.stream().map(item -> toResponse(item, type)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB keyword discover failed: " + e.getMessage(), e);
		}
	}

	// All streaming services TMDB knows for a region and media type (#270),
	// with region-local display priority — feeds the settings picker and the
	// id -> name/logo lookup for availability badges.
	public List<TmdbWatchProvider> getWatchProviders(TitleType type, String watchRegion) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbWatchProviderListResponse response = restClient.get()
				.uri("/3/watch/providers/{mediaType}?language=en-US&watch_region={region}", mediaType, watchRegion)
				.retrieve()
				.body(TmdbWatchProviderListResponse.class);
			return response != null && response.results() != null ? response.results() : List.of();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB watch provider list failed: " + e.getMessage(), e);
		}
	}

	// The genre catalog for a media type (#323). Titles carry bare genre ids
	// everywhere else in TMDB's API — this endpoint is the only source of the names,
	// which the stats page's genre breakdown needs. The catalog changes on the order
	// of years; GenreCatalogService caches it in-process.
	public List<TmdbGenre> getGenres(TitleType type) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbGenreListResponse response = restClient.get()
				.uri("/3/genre/{mediaType}/list?language=en-US", mediaType)
				.retrieve()
				.body(TmdbGenreListResponse.class);
			return response != null && response.genres() != null ? response.genres() : List.of();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB genre list failed: " + e.getMessage(), e);
		}
	}

	public List<TmdbWatchRegion> getWatchRegions() {
		try {
			TmdbWatchRegionListResponse response = restClient.get()
				.uri("/3/watch/providers/regions?language=en-US")
				.retrieve()
				.body(TmdbWatchRegionListResponse.class);
			return response != null && response.results() != null ? response.results() : List.of();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB watch region list failed: " + e.getMessage(), e);
		}
	}

	// Full keyword objects, not just ids (#271): names label keyword-seeded shelves
	public List<TmdbKeyword> getKeywords(TitleType type, String tmdbId) {
		String mediaType = type == TitleType.MOVIE ? "movie" : "tv";
		try {
			TmdbKeywordsResponse response = restClient.get()
				.uri("/3/{mediaType}/{id}/keywords", mediaType, tmdbId)
				.retrieve()
				.body(TmdbKeywordsResponse.class);
			if (response == null) return List.of();
			return response.keywords() != null ? response.keywords()
				: response.results() != null ? response.results() : List.of();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB keywords failed: " + e.getMessage(), e);
		}
	}

	// Franchise-continuation shelf (#272): one call per compute, cacheable by
	// TMDB's own CDN. Parts share TmdbItem's shape (movie search-result fields),
	// so the same toResponse conversion applies; the seed movie itself is
	// filtered out downstream by the suggestion pipeline's "owned" dedup set.
	public List<TitleSearchResponse> getCollectionParts(int collectionId) {
		try {
			TmdbCollectionDetail detail = restClient.get()
				.uri("/3/collection/{id}?language=en-US", collectionId)
				.retrieve()
				.body(TmdbCollectionDetail.class);
			List<TmdbItem> parts = detail != null && detail.parts() != null ? detail.parts() : List.of();
			return parts.stream().map(item -> toResponse(item, TitleType.MOVIE)).toList();
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get collection failed: " + e.getMessage(), e);
		}
	}

	// Person page (#304). combined_credits carries movie and TV credits alike,
	// which discoverByPerson cannot: /3/discover/tv has no people filter, so the
	// suggestion shelf's with_people call would drop every TV credit here.
	public TmdbPersonDetail getPersonDetail(int personId) {
		try {
			TmdbPersonDetail detail = restClient.get()
				.uri("/3/person/{id}?language=en-US&append_to_response=combined_credits", personId)
				.retrieve()
				.body(TmdbPersonDetail.class);
			if (detail == null) {
				throw new TmdbApiException("TMDB returned null for person " + personId, null);
			}
			return detail;
		} catch (RestClientException e) {
			throw new TmdbApiException("TMDB get person detail failed: " + e.getMessage(), e);
		}
	}

	public static String posterUrl(String posterPath) {
		return posterPath != null ? POSTER_BASE_URL + posterPath : null;
	}

	public static String stillUrl(String stillPath) {
		return stillPath != null ? STILL_BASE_URL + stillPath : null;
	}

	public static String providerLogoUrl(String logoPath) {
		return logoPath != null ? PROVIDER_LOGO_BASE_URL + logoPath : null;
	}

	// Null for cast members TMDB has no headshot for — the client renders a
	// silhouette placeholder in that case (#295)
	public static String profileUrl(String profilePath) {
		return profilePath != null ? PROFILE_BASE_URL + profilePath : null;
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
		LocalDate releaseDate = TmdbDates.parse(dateStr);
		String posterUrl = item.posterPath() != null ? POSTER_BASE_URL + item.posterPath() : null;

		return new TitleSearchResponse(
			String.valueOf(item.id()),
			"TMDB",
			type,
			name,
			item.overview(),
			releaseDate,
			posterUrl,
			item.genreIds() != null ? item.genreIds() : List.of()
		);
	}

}
