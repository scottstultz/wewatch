package com.wewatch.api.tmdb;

import java.util.Comparator;

/**
 * Reduces a title's videos block to the single YouTube trailer worth linking to (#340).
 *
 * <p>TMDB routinely returns a dozen videos per title — trailers, teasers, clips,
 * featurettes, bloopers — so the detail endpoint picks one rather than shipping the list.
 * An official trailer wins; a teaser is the fallback; anything that doesn't answer
 * "should I watch this?" is dropped. Ties break on the most recently published video,
 * which is the one for the current release.
 */
public final class TrailerPicker {

	private static final String YOUTUBE = "YouTube";
	private static final String TRAILER = "Trailer";
	private static final String TEASER = "Teaser";
	private static final String WATCH_URL = "https://www.youtube.com/watch?v=";

	private TrailerPicker() {}

	/**
	 * @return a YouTube watch URL, or null when the title has no linkable video —
	 *         including when TMDB returned no videos block at all.
	 */
	public static String trailerUrl(TmdbVideos videos) {
		if (videos == null || videos.results() == null) return null;
		return videos.results().stream()
			.filter(TrailerPicker::linkable)
			.max(Comparator.<TmdbVideo>comparingInt(TrailerPicker::rank)
				// Nulls sort as "" — lexicographic order on an ISO-8601 timestamp is
				// chronological, and an undated video loses to a dated one.
				.thenComparing(v -> v.publishedAt() != null ? v.publishedAt() : ""))
			.map(v -> WATCH_URL + v.key())
			.orElse(null);
	}

	// Only YouTube: the link hands off to the YouTube app on mobile, and Vimeo
	// and friends carry the odd non-trailer TMDB hasn't typed correctly.
	private static boolean linkable(TmdbVideo video) {
		return video != null
			&& video.key() != null && !video.key().isBlank()
			&& YOUTUBE.equalsIgnoreCase(video.site())
			&& (TRAILER.equalsIgnoreCase(video.type()) || TEASER.equalsIgnoreCase(video.type()));
	}

	// Higher is better. An unofficial trailer still beats an official teaser — a
	// fan-uploaded trailer is a trailer; a teaser shows less by definition.
	private static int rank(TmdbVideo video) {
		boolean official = Boolean.TRUE.equals(video.official());
		if (TRAILER.equalsIgnoreCase(video.type())) return official ? 3 : 2;
		return official ? 1 : 0;
	}
}
