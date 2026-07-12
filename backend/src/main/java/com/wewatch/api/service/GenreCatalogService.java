package com.wewatch.api.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbGenre;

/**
 * TMDB genre id -&gt; name (#323).
 *
 * <p>Every cached title stores bare genre ids ({@code tmdb_title_cache.genre_ids}); the names
 * live only on TMDB's genre-list endpoint. The stats page's breakdown needs the names, so this
 * caches the whole catalog in-process — the same shape as {@link WatchProviderService}, and for
 * the same reason: it is a small list (~40 entries) that changes on the order of years, so one
 * pair of TMDB calls per TTL window beats two per page view.
 *
 * <p>The movie and TV catalogs are merged into one map. Their id spaces overlap only where the
 * names agree (16 Animation, 18 Drama, 35 Comedy…), and the ids unique to TV — 10759 "Action &amp;
 * Adventure", 10765 "Sci-Fi &amp; Fantasy" — simply carry their own names, so a single flat map
 * is unambiguous and callers don't need to know a title's medium to label it.
 */
@Service
public class GenreCatalogService {

	private static final Logger log = LoggerFactory.getLogger(GenreCatalogService.class);
	private static final String CATALOG_KEY = "catalog";

	private final TmdbClient tmdbClient;
	private final Cache<String, Map<Integer, String>> catalogCache;

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
	 * Genre names by TMDB id. Never throws: a TMDB outage yields an empty map, which costs the
	 * stats page its genre breakdown but still lets the counts and watch time render — the
	 * numbers are the point, and they come entirely from our own tables.
	 */
	public Map<Integer, String> genreNames() {
		// Returning null from the mapping function tells Caffeine to record nothing, so a TMDB
		// outage is retried on the next request rather than poisoning the cache with an empty
		// catalog for the whole TTL window.
		Map<Integer, String> names = catalogCache.get(CATALOG_KEY, k -> {
			try {
				Map<Integer, String> byId = new LinkedHashMap<>();
				for (TitleType type : List.of(TitleType.MOVIE, TitleType.TV)) {
					for (TmdbGenre genre : tmdbClient.getGenres(type)) {
						byId.putIfAbsent(genre.id(), genre.name());
					}
				}
				return byId;
			} catch (RuntimeException e) {
				log.warn("TMDB genre catalog unavailable, stats will render without genre names: {}",
					e.getMessage());
				return null;
			}
		});
		return names != null ? names : Map.of();
	}
}
