package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvDetail(
	long id,
	@JsonProperty("number_of_seasons") int numberOfSeasons,
	String status,
	@JsonProperty("first_air_date") String firstAirDate,
	List<TmdbTvSeason> seasons,
	String name,
	String overview,
	@JsonProperty("poster_path") String posterPath,
	List<TmdbGenre> genres,
	@JsonProperty("vote_average") Double voteAverage,
	@JsonProperty("vote_count") Integer voteCount,
	TmdbCredits credits,
	@JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
	// Trailers and teasers (#340); null when TMDB has no videos for the title
	TmdbVideos videos
) {}
