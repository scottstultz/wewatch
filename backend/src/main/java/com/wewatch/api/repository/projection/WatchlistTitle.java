package com.wewatch.api.repository.projection;

/**
 * One row per watchlist entry: the title it points at, its medium, and where the user has got
 * to (#323).
 *
 * <p>Just enough to answer "how many movies / shows are finished, and which titles' runtimes do
 * I need to look up" — deliberately not an aggregate. The counting, the WATCHED filter and the
 * MOVIE/TV split all happen in {@code StatsService}, where the mocked-repo tests can reach them;
 * no test in this suite executes SQL.
 */
public interface WatchlistTitle {

	String getExternalId();

	/** {@code TitleType} as its string name — a native query yields the raw column. */
	String getType();

	/** {@code WatchStatus} as its string name — a native query yields the raw column. */
	String getStatus();
}
