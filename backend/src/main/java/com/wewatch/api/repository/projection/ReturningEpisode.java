package com.wewatch.api.repository.projection;

/**
 * An episode of a WATCHING TV entry airing inside a date window (#321).
 *
 * <p>Unlike {@link NextEpisode}, this is independent of watch progress: it is the
 * next episode <em>to air</em>, not the next episode to watch. A user who is
 * behind on a returning show still needs to know the show is back.
 */
public interface ReturningEpisode {

	Long getEntryId();

	String getExternalId();

	String getExternalSource();

	String getShowName();

	String getPosterUrl();

	Integer getSeasonNumber();

	Integer getEpisodeNumber();

	String getEpisodeName();

	// java.sql.Date rather than LocalDate: the value comes from a native query
	// tuple, which yields the JDBC date type as-is
	java.sql.Date getAirDate();

	Integer getRuntimeMinutes();
}
