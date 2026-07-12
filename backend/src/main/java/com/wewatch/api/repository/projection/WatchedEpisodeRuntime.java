package com.wewatch.api.repository.projection;

/**
 * One row per <em>watched</em> episode on a watchlist: which show it belongs to, and how long it
 * ran (#323).
 *
 * <p>{@code runtimeMinutes} is null when the episode has no cached row, or when TMDB has no
 * runtime for it. That is the whole point of the left join behind this projection: the episode
 * still counts toward "episodes finished", it just contributes nothing to watch time. Dropping
 * those rows in SQL instead would silently under-count the thing users are proudest of.
 */
public interface WatchedEpisodeRuntime {

	/** TMDB id of the *show*, not the episode — the key back into {@code tmdb_title_cache}. */
	String getExternalId();

	Integer getRuntimeMinutes();
}
