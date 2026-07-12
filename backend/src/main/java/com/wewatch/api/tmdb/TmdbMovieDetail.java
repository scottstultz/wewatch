package com.wewatch.api.tmdb;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieDetail(
	long id,
	String title,
	String overview,
	@JsonProperty("poster_path") String posterPath,
	String status,
	@JsonProperty("release_date") String releaseDate,
	List<TmdbGenre> genres,
	@JsonProperty("vote_average") Double voteAverage,
	@JsonProperty("vote_count") Integer voteCount,
	TmdbCredits credits,
	@JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
	@JsonProperty("belongs_to_collection") TmdbCollectionRef belongsToCollection,
	// Total runtime in minutes; null or 0 for titles TMDB has no runtime for
	Integer runtime
) {}
