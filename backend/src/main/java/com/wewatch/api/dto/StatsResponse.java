package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a watchlist has watched: counts, time, and a genre breakdown (#323). "
	+ "Scoped to the watchlist rather than the caller, because episode progress is stored per "
	+ "entry, not per member — on a shared list these are the household's numbers.")
public record StatsResponse(
	@Schema(description = "Movies in WATCHED status")
	int moviesFinished,
	@Schema(description = "Shows in WATCHED status. Counted from entry status, so a show you "
		+ "marked finished without ticking its episodes still counts here — it just adds no watch time")
	int showsFinished,
	@Schema(description = "Episodes ticked as watched, on any entry regardless of status: being "
		+ "40 episodes into an unfinished show still means 40 episodes watched")
	int episodesFinished,
	@Schema(description = "movieMinutes + episodeMinutes")
	int totalMinutes,
	int movieMinutes,
	int episodeMinutes,
	@Schema(description = "Watched movies and episodes with no runtime in the cache. They are "
		+ "included in the counts above but contribute nothing to the minutes, so this is how far "
		+ "the time totals can be trusted")
	int itemsMissingRuntime,
	@Schema(description = "Watch time per genre, largest first. A title counts in every genre it "
		+ "carries, so these deliberately sum to more than totalMinutes — they are a shape, not a "
		+ "partition, which is why they are minutes and not percentages")
	List<GenreStat> genres
) {

	@Schema(description = "One genre's share of watch time.")
	public record GenreStat(
		@Schema(description = "TMDB genre id")
		int genreId,
		String name,
		int minutes,
		@Schema(description = "Distinct titles contributing to this genre — movies finished plus "
			+ "shows with at least one watched episode")
		int titleCount
	) {
	}
}
