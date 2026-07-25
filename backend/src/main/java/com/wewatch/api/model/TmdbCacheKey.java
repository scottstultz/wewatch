package com.wewatch.api.model;

import java.util.Locale;

/**
 * The medium-scoped identity of a TMDB title (#394).
 *
 * <p>TMDB's movie and TV id sequences are <em>independent namespaces</em> — the same integer
 * identifies a different title in each medium. Movie 550 is Fight Club; TV 550 is Till Death Us Do
 * Part. Movie 1396 is Mirror; TV 1396 is Breaking Bad. A bare id is therefore not an identity, and
 * every table that treated it as one let whichever medium was written first own the id for the
 * other.
 *
 * <p>{@code tmdb_title_cache} is keyed on {@code movie:550} / {@code tv:550} from V26 onward, so
 * both media can hold a row at the same TMDB id and a prewarm of one cannot flip or overwrite the
 * other. That makes the cache's consumers correct <em>by construction</em>: a movie asking for
 * {@code movie:550} simply does not find a TV row, so there is nothing left for a per-reader
 * medium guard to catch.
 *
 * <p>The prefix is the lowercased {@link TitleType} name, which keeps the key readable in psql and
 * means the SQL side can derive it with {@code lower(type) || ':' || tmdb_id} — the form V26 uses
 * to migrate existing rows.
 *
 * <p>{@code tmdb_season_cache} and {@code tmdb_episode_cache} are TV-only and hang off the same
 * key, so they carry the {@code tv:} form too and their FK to the parent survives unchanged in
 * shape. That is also why the native joins onto them read {@code 'tv:' || t.external_id}: a movie
 * sharing an id with a show can no longer pick up the show's episodes.
 */
public final class TmdbCacheKey {

	public static final String SEPARATOR = ":";

	private TmdbCacheKey() {
	}

	/** The cache key for a TMDB id in a given medium, e.g. {@code (MOVIE, "550")} → {@code movie:550}. */
	public static String of(TitleType type, String tmdbId) {
		if (type == null || tmdbId == null) return null;
		return prefix(type) + tmdbId;
	}

	/** The cache key for a TV show. Season and episode cache rows share it. */
	public static String tv(String tmdbId) {
		return of(TitleType.TV, tmdbId);
	}

	/** The cache key for a movie. */
	public static String movie(String tmdbId) {
		return of(TitleType.MOVIE, tmdbId);
	}

	/** The cache key for a locally-resolved title, taking the medium from the row itself. */
	public static String of(Title title) {
		if (title == null) return null;
		return of(title.getType(), title.getExternalId());
	}

	/**
	 * The bare TMDB id inside a cache key — what {@code TmdbClient} needs, since TMDB's own API is
	 * already medium-scoped by its {@code /3/movie/} vs {@code /3/tv/} path. Tolerates an
	 * un-prefixed value so a caller handed a raw id still gets a usable one back.
	 */
	public static String tmdbIdOf(String cacheKey) {
		if (cacheKey == null) return null;
		int sep = cacheKey.indexOf(SEPARATOR);
		return sep < 0 ? cacheKey : cacheKey.substring(sep + SEPARATOR.length());
	}

	/** The key prefix for a medium, {@code "movie:"} or {@code "tv:"}. */
	public static String prefix(TitleType type) {
		return type.name().toLowerCase(Locale.ROOT) + SEPARATOR;
	}
}
