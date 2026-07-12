package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.wewatch.api.dto.StatsResponse;
import com.wewatch.api.dto.StatsResponse.GenreStat;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TitleRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.projection.WatchedEpisodeRuntime;
import com.wewatch.api.repository.projection.WatchlistTitle;

/**
 * What a watchlist has watched (#323): counts, watch time, and a genre breakdown.
 *
 * <p>Reads only our own tables — the two queries below plus a batch load of the title cache — so
 * the page costs no TMDB traffic. The only outbound call is the genre <em>catalog</em>, which is
 * cached in-process for a day and degrades to no-names rather than failing the request.
 *
 * <p>All the arithmetic lives here rather than in SQL. That is the same call {@code
 * ReturningEpisodeService} makes and for the same reason: this suite mocks every repository and no
 * test executes SQL, so a {@code SUM}/{@code GROUP BY} in the query would be logic nothing could
 * check.
 */
@Service
public class StatsService {

	private final TitleRepository titleRepository;
	private final EpisodeProgressRepository episodeProgressRepository;
	private final TmdbTitleCacheRepository titleCacheRepository;
	private final GenreCatalogService genreCatalogService;

	public StatsService(
		TitleRepository titleRepository,
		EpisodeProgressRepository episodeProgressRepository,
		TmdbTitleCacheRepository titleCacheRepository,
		GenreCatalogService genreCatalogService
	) {
		this.titleRepository = titleRepository;
		this.episodeProgressRepository = episodeProgressRepository;
		this.titleCacheRepository = titleCacheRepository;
		this.genreCatalogService = genreCatalogService;
	}

	public StatsResponse statsFor(Long watchlistId) {
		// Finished titles come from entry status; watched episodes come from progress rows. The
		// two are independent on purpose — see the field docs on StatsResponse.
		Set<String> finishedMovieIds = new LinkedHashSet<>();
		Set<String> finishedShowIds = new LinkedHashSet<>();
		for (WatchlistTitle entry : titleRepository.findWatchlistTitles(watchlistId)) {
			if (!WatchStatus.WATCHED.name().equals(entry.getStatus())) continue;
			if (TitleType.MOVIE.name().equals(entry.getType())) {
				finishedMovieIds.add(entry.getExternalId());
			} else if (TitleType.TV.name().equals(entry.getType())) {
				finishedShowIds.add(entry.getExternalId());
			}
		}

		// Sets, not row counts: the same title can sit on a watchlist more than once, and it is
		// one movie however many times it was added.
		int missingRuntime = 0;

		List<WatchedEpisodeRuntime> watchedEpisodes =
			episodeProgressRepository.findWatchedEpisodeRuntimes(watchlistId);
		int episodesFinished = watchedEpisodes.size();
		int episodeMinutes = 0;

		// Time per *show*, so a show's episodes attribute to its genres as one lump. A show with
		// only runtime-less episodes still lands here with 0 minutes, which keeps it in the genre
		// breakdown's title counts.
		Map<String, Integer> minutesByShow = new LinkedHashMap<>();
		for (WatchedEpisodeRuntime episode : watchedEpisodes) {
			int minutes = positiveOrZero(episode.getRuntimeMinutes());
			if (minutes == 0) missingRuntime++;
			episodeMinutes += minutes;
			minutesByShow.merge(episode.getExternalId(), minutes, Integer::sum);
		}

		Set<String> contributingIds = new LinkedHashSet<>(finishedMovieIds);
		contributingIds.addAll(minutesByShow.keySet());
		Map<String, TmdbTitleCache> cacheById = titleCacheRepository.findAllById(contributingIds)
			.stream()
			.collect(Collectors.toMap(TmdbTitleCache::getTmdbId, Function.identity()));

		Map<Integer, GenreTally> tallies = new LinkedHashMap<>();

		int movieMinutes = 0;
		for (String movieId : finishedMovieIds) {
			TmdbTitleCache cached = cacheById.get(movieId);
			int minutes = cached != null ? positiveOrZero(cached.getRuntimeMinutes()) : 0;
			if (minutes == 0) missingRuntime++;
			movieMinutes += minutes;
			attributeToGenres(tallies, cached, minutes);
		}
		for (Map.Entry<String, Integer> show : minutesByShow.entrySet()) {
			attributeToGenres(tallies, cacheById.get(show.getKey()), show.getValue());
		}

		return new StatsResponse(
			finishedMovieIds.size(),
			finishedShowIds.size(),
			episodesFinished,
			movieMinutes + episodeMinutes,
			movieMinutes,
			episodeMinutes,
			missingRuntime,
			toGenreStats(tallies)
		);
	}

	/**
	 * Adds a title's watch time to each of its genres. A title carrying three genres adds its full
	 * time to all three — the breakdown is a shape ("what do we spend evenings on"), not a
	 * partition of the total, which is why the response is minutes rather than percentages.
	 */
	private void attributeToGenres(Map<Integer, GenreTally> tallies, TmdbTitleCache cached, int minutes) {
		if (cached == null || cached.getGenreIds() == null) return;
		for (Integer genreId : cached.getGenreIds()) {
			tallies.computeIfAbsent(genreId, id -> new GenreTally()).add(minutes);
		}
	}

	private List<GenreStat> toGenreStats(Map<Integer, GenreTally> tallies) {
		Map<Integer, String> names = genreCatalogService.genreNames();
		List<GenreStat> stats = new ArrayList<>();
		for (Map.Entry<Integer, GenreTally> tally : tallies.entrySet()) {
			// An id with no name is an id we can't label: a genre TMDB has retired, or the whole
			// catalog being unavailable. Dropping it beats rendering a bar labelled "10402".
			String name = names.get(tally.getKey());
			if (name == null) continue;
			stats.add(new GenreStat(tally.getKey(), name, tally.getValue().minutes, tally.getValue().titles));
		}
		stats.sort(Comparator.comparingInt(GenreStat::minutes).reversed()
			.thenComparing(GenreStat::name));
		return stats;
	}

	// TMDB reports "unknown runtime" as either null or 0, and both mean the same thing here.
	private int positiveOrZero(Integer minutes) {
		return minutes != null && minutes > 0 ? minutes : 0;
	}

	private static final class GenreTally {
		private int minutes;
		private int titles;

		void add(int minutes) {
			this.minutes += minutes;
			this.titles++;
		}
	}
}
