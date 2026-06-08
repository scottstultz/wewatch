package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvSeason(
	long id,
	@JsonProperty("season_number") int seasonNumber,
	String name,
	String overview,
	@JsonProperty("poster_path") String posterPath,
	@JsonProperty("episode_count") Integer episodeCount,
	@JsonProperty("air_date") String airDate,
	List<TmdbTvEpisode> episodes
) {
}
