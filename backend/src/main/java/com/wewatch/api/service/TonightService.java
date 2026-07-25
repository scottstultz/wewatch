package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.wewatch.api.dto.TonightPickResponse;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.projection.NextEpisode;

/**
 * "What can I finish tonight?" (#359): the entries on a watchlist that fit a time budget.
 *
 * <p>A movie is judged on its own runtime, a show on the runtime of the episode the user would
 * put on next — the two questions an evening actually asks. Both numbers are already cached
 * ({@code tmdb_title_cache.runtime_minutes} from #323, {@code tmdb_episode_cache.runtime_minutes}
 * from the show prewarm), so this costs no TMDB traffic.
 *
 * <p><strong>A title with no known runtime is left out, not let through.</strong> It cannot be
 * judged to fit, and a picker that offers something it hasn't measured is worse than one that
 * offers less.
 *
 * <p>The status filter and the fit comparison live here rather than in SQL, the same call
 * {@code ReturningEpisodeService} and {@code StatsService} make and for the same reason: this
 * suite mocks every repository and no test executes SQL, so rules pushed into a query are rules
 * nothing can check.
 */
@Service
public class TonightService {

	static final int MIN_MINUTES = 1;
	static final int MAX_MINUTES = 600;

	/**
	 * The statuses the picker draws from. WATCHED is excluded: the question is what to put on
	 * tonight, and a finished title has already had its evening.
	 */
	private static final Set<WatchStatus> PICKABLE =
		Set.of(WatchStatus.WANT_TO_WATCH, WatchStatus.WATCHING);

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final TitleService titleService;
	private final TmdbTitleCacheRepository titleCacheRepository;
	private final EpisodeProgressRepository episodeProgressRepository;

	public TonightService(
		WatchlistEntryRepository watchlistEntryRepository,
		TitleService titleService,
		TmdbTitleCacheRepository titleCacheRepository,
		EpisodeProgressRepository episodeProgressRepository
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.titleService = titleService;
		this.titleCacheRepository = titleCacheRepository;
		this.episodeProgressRepository = episodeProgressRepository;
	}

	/**
	 * Entries that fit in {@code maxMinutes} or less, shortest first.
	 *
	 * <p>Inclusive on purpose: a 90-minute film fits a 90-minute window. Ties break on entry id so
	 * the order is stable across calls.
	 *
	 * @throws IllegalArgumentException if {@code maxMinutes} is outside [1, 600]
	 */
	public List<TonightPickResponse> fitsWithin(Long watchlistId, int maxMinutes) {
		if (maxMinutes < MIN_MINUTES || maxMinutes > MAX_MINUTES) {
			throw new IllegalArgumentException(
				"maxMinutes must be between " + MIN_MINUTES + " and " + MAX_MINUTES + ", got " + maxMinutes
			);
		}

		List<WatchlistEntry> entries = watchlistEntryRepository
			.findByWatchlistId(watchlistId, null, Pageable.unpaged())
			.getContent()
			.stream()
			.filter(e -> PICKABLE.contains(e.getStatus()))
			.toList();
		if (entries.isEmpty()) {
			return List.of();
		}

		Map<Long, Title> titlesById = titleService.findByIds(
			entries.stream().map(WatchlistEntry::getTitleId).collect(Collectors.toList())
		);

		List<WatchlistEntry> movies = new ArrayList<>();
		List<WatchlistEntry> shows = new ArrayList<>();
		for (WatchlistEntry entry : entries) {
			Title title = titlesById.get(entry.getTitleId());
			if (title == null || title.getType() == null) continue;
			if (title.getType() == TitleType.MOVIE) {
				movies.add(entry);
			} else if (title.getType() == TitleType.TV) {
				shows.add(entry);
			}
		}

		List<TonightPickResponse> picks = new ArrayList<>();
		picks.addAll(moviePicks(movies, titlesById, maxMinutes));
		picks.addAll(showPicks(shows, maxMinutes));
		picks.sort(Comparator.comparing(TonightPickResponse::runtimeMinutes)
			.thenComparing(TonightPickResponse::entryId));
		return picks;
	}

	private List<TonightPickResponse> moviePicks(
		List<WatchlistEntry> movies, Map<Long, Title> titlesById, int maxMinutes
	) {
		if (movies.isEmpty()) {
			return List.of();
		}
		// One batch read for the page, keyed on the medium-scoped cache key (#394) — the same
		// shape StatsService uses. This path is movie-only, so every key takes the "movie:" form.
		Set<String> cacheKeys = movies.stream()
			.map(e -> TmdbCacheKey.movie(titlesById.get(e.getTitleId()).getExternalId()))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, TmdbTitleCache> cacheById = titleCacheRepository.findAllById(cacheKeys).stream()
			.collect(Collectors.toMap(TmdbTitleCache::getTmdbId, Function.identity()));

		List<TonightPickResponse> picks = new ArrayList<>();
		for (WatchlistEntry entry : movies) {
			TmdbTitleCache cached = cacheById.get(TmdbCacheKey.movie(titlesById.get(entry.getTitleId()).getExternalId()));
			Integer runtime = cached != null ? cached.getRuntimeMinutes() : null;
			if (fits(runtime, maxMinutes)) {
				picks.add(new TonightPickResponse(entry.getId(), TitleType.MOVIE, runtime, null, null));
			}
		}
		return picks;
	}

	private List<TonightPickResponse> showPicks(List<WatchlistEntry> shows, int maxMinutes) {
		if (shows.isEmpty()) {
			return List.of();
		}
		// findNextUnwatchedEpisodeByEntryIds, not findNextEpisodeByEntryIds: most of a "Want to
		// Watch" list has never been started, and those shows still have an episode 1 to offer.
		Map<Long, NextEpisode> nextByEntryId = new HashMap<>();
		List<Long> entryIds = shows.stream().map(WatchlistEntry::getId).collect(Collectors.toList());
		for (NextEpisode next : episodeProgressRepository.findNextUnwatchedEpisodeByEntryIds(entryIds)) {
			nextByEntryId.put(next.getEntryId(), next);
		}

		List<TonightPickResponse> picks = new ArrayList<>();
		for (WatchlistEntry entry : shows) {
			// Absent for a show that is caught up, and for one whose episodes were never cached.
			NextEpisode next = nextByEntryId.get(entry.getId());
			if (next == null) continue;
			Integer runtime = next.getRuntimeMinutes();
			if (fits(runtime, maxMinutes)) {
				picks.add(new TonightPickResponse(
					entry.getId(), TitleType.TV, runtime, next.getSeasonNumber(), next.getEpisodeNumber()
				));
			}
		}
		return picks;
	}

	/** A null or nonsensical runtime is unknown, and unknown never fits. */
	private boolean fits(Integer runtimeMinutes, int maxMinutes) {
		return runtimeMinutes != null && runtimeMinutes > 0 && runtimeMinutes <= maxMinutes;
	}
}
