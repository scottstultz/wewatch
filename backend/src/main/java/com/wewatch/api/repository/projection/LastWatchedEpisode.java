package com.wewatch.api.repository.projection;

/**
 * The most recently watched episode of a watchlist entry.
 */
public interface LastWatchedEpisode {

	Long getEntryId();

	Integer getSeasonNumber();

	Integer getEpisodeNumber();
}
