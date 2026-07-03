package com.wewatch.api.repository.projection;

/**
 * Per-entry episode counts: total tracked episodes and how many are watched.
 */
public interface EpisodeProgressCounts {

	Long getEntryId();

	Long getTotal();

	Long getWatched();
}
