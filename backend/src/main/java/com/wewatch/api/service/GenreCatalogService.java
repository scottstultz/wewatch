package com.wewatch.api.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.dto.GenreResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

/**
 * TMDB's genre catalog (#323; per-type accessor added in #381).
 *
 * <p>Every cached title stores bare genre ids ({@code tmdb_title_cache.genre_ids}); the names
 * live only on TMDB's genre-list endpoint. The stats page's breakdown needs the names, so this
 * caches the whole catalog in-process — the same shape as {@link WatchProviderService}, and for
 * the same reason: it is a small list (~40 entries) that changes on the order of years, so one
 * pair of TMDB calls per TTL window beats two per page view.
 *
 * <p>Two views of the same fetch, because callers ask two different questions:
 *
 * <ul>
 *   <li>{@link #genreNames()} merges both catalogs into one id-&gt;name map. Their id spaces overlap
 *       only where the names agree (16 Animation, 18 Drama, 35 Comedy…), and the ids unique to TV —
 *       10759 "Action &amp; Adventure", 10765 "Sci-Fi &amp; Fantasy" — simply carry their own names,
 *       so a single flat map is unambiguous. This is what a caller wants when it holds an id and
 *       needs a label: the stats breakdown, and the Library's genre panel, where one watchlist holds
 *       both movies and shows.
 *   <li>{@link #genresFor(TitleType)} keeps them apart. A <em>picker</em> cannot use the merged map:
 *       movie 28 "Action" and TV 10759 "Action &amp; Adventure" are distinct genres, and offering
 *       both in one list gives the user no way to tell which is which.
 * </ul>
 *
 * <p>Both views come from one cached fetch, so the per-type accessor cost no extra TMDB traffic —
 * and neither view can throw (see {@link #load()}).
 */
@Service
public class GenreCatalogService {

	private static final Logger log = LoggerFactory.getLogger(GenreCatalogService.class);
	private static final String CATALOG_KEY = "catalog";
	private static final Catalog EMPTY = new Catalog(List.of(), List.of(), Map.of());

	private final TmdbClient tmdbClient;
	private final Cache<String, Catalog> catalogCache;

	public GenreCatalogService(
		TmdbClient tmdbClient,
		@Value("${tmdb.genres.ttl-hours:24}") long ttlHours
	) {
		this.tmdbClient = tmdbClient;
		this.catalogCache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofHours(ttlHours))
			.maximumSize(1)
			.build();
	}

	/**
	 * Genre names by TMDB id, both catalogs merged. Never throws: a TMDB outage yields an empty map,
	 * which costs the stats page its genre breakdown but still lets the counts and watch time
	 * render — the numbers are the point, and they come entirely from our own tables.
	 */
	public Map<Integer, String> genreNames() {
		return load().names();
	}

	/**
	 * One medium's catalog, sorted by name. Never throws, for the same reason as
	 * {@link #genreNames()}: a genre picker with no options is a poorer page, not a failed request.
	 */
	public List<GenreResponse> genresFor(TitleType type) {
		Catalog catalog = load();
		return type == TitleType.MOVIE ? catalog.movie() : catalog.tv();
	}

	private Catalog load() {
		// Returning null from the mapping function tells Caffeine to record nothing, so a TMDB
		// outage is retried on the next request rather than poisoning the cache with an empty
		// catalog for the whole TTL window.
		Catalog catalog = catalogCache.get(CATALOG_KEY, k -> {
			try {
				List<GenreResponse> movie = fetch(TitleType.MOVIE);
				List<GenreResponse> tv = fetch(TitleType.TV);
				// Movie first, then putIfAbsent for TV: an id in both catalogs keeps the movie
				// name. The two agree wherever they overlap, so this only pins the precedence —
				// but it pins the one StatsService has read since #323.
				Map<Integer, String> names = new LinkedHashMap<>();
				for (GenreResponse genre : movie) {
					names.putIfAbsent(genre.id(), genre.name());
				}
				for (GenreResponse genre : tv) {
					names.putIfAbsent(genre.id(), genre.name());
				}
				return new Catalog(movie, tv, names);
			} catch (RuntimeException e) {
				log.warn("TMDB genre catalog unavailable, stats will render without genre names: {}",
					e.getMessage());
				return null;
			}
		});
		return catalog != null ? catalog : EMPTY;
	}

	private List<GenreResponse> fetch(TitleType type) {
		return tmdbClient.getGenres(type).stream()
			.map(g -> new GenreResponse(g.id(), g.name()))
			.sorted(Comparator.comparing(GenreResponse::name))
			.toList();
	}

	/**
	 * One TMDB fetch pair in all three shapes its callers need, cached as a unit so the per-type
	 * lists and the merged map can never disagree about what the catalog says.
	 */
	private record Catalog(List<GenreResponse> movie, List<GenreResponse> tv, Map<Integer, String> names) {
	}
}
