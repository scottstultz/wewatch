package com.wewatch.api.repository.projection;

/**
 * The next unwatched episode of a watchlist entry in air-date order.
 */
public interface NextEpisode {

	Long getEntryId();

	Integer getSeasonNumber();

	Integer getEpisodeNumber();

	String getName();

	// java.sql.Date rather than LocalDate: the value comes from a native query
	// tuple, which yields the JDBC date type as-is
	java.sql.Date getAirDate();

	Integer getRuntimeMinutes();
}
