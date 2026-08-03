package com.wewatch.api.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.wewatch.api.dto.ReturningEpisodeResponse;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.projection.ReturningEpisode;

/**
 * "Returning this week" (#321): the shows on a watchlist that are about to drop a
 * new episode.
 *
 * <p>This deliberately ignores watch progress. {@code EpisodeProgressSummary.nextAirDate}
 * already on every entry is the air date of the next <em>unwatched</em> episode, so for
 * anyone behind on a returning show it is a date in the past — it would hide exactly the
 * shows this feature exists to surface. The query here asks the cache a different
 * question: what airs next, regardless of what you've seen.
 *
 * <p>It also deliberately ignores watchlist entry status (#416): Watching, Watched, and
 * Want to Watch all qualify. A show a household has fully caught up on and parked in
 * Watched — or in Want to Watch, used informally as "waiting on more" — still needs to
 * surface here when a new season is announced.
 *
 * <p>Reads only cached episode rows, so it costs no TMDB traffic. {@code
 * EpisodeCacheRefreshJob} is what keeps that cache current enough for the answer to
 * be true.
 */
@Service
public class ReturningEpisodeService {

	static final int MIN_DAYS = 1;
	static final int MAX_DAYS = 30;

	private final TmdbEpisodeCacheRepository tmdbEpisodeCacheRepository;
	private final Clock clock;

	public ReturningEpisodeService(TmdbEpisodeCacheRepository tmdbEpisodeCacheRepository, Clock clock) {
		this.tmdbEpisodeCacheRepository = tmdbEpisodeCacheRepository;
		this.clock = clock;
	}

	/**
	 * Shows with an episode airing within {@code days} of today, soonest first, one row
	 * per show — a show mid-season has several episodes in a 30-day window, but only the
	 * next one is news.
	 *
	 * @throws IllegalArgumentException if {@code days} is outside [1, 30]
	 */
	public List<ReturningEpisodeResponse> returningWithin(Long watchlistId, int days) {
		if (days < MIN_DAYS || days > MAX_DAYS) {
			throw new IllegalArgumentException(
				"days must be between " + MIN_DAYS + " and " + MAX_DAYS + ", got " + days
			);
		}
		LocalDate from = LocalDate.now(clock);
		LocalDate to = from.plusDays(days);

		List<ReturningEpisode> upcoming = tmdbEpisodeCacheRepository
			.findUpcomingByWatchlistId(watchlistId, from, to);

		// The query returns air-date order, so the first row seen for an entry is its
		// soonest episode.
		Set<Long> seen = new HashSet<>();
		List<ReturningEpisodeResponse> result = new ArrayList<>();
		for (ReturningEpisode episode : upcoming) {
			if (episode.getAirDate() == null || !seen.add(episode.getEntryId())) {
				continue;
			}
			result.add(new ReturningEpisodeResponse(
				episode.getEntryId(),
				episode.getExternalId(),
				episode.getExternalSource(),
				episode.getShowName(),
				episode.getPosterUrl(),
				episode.getSeasonNumber(),
				episode.getEpisodeNumber(),
				episode.getEpisodeName(),
				episode.getAirDate().toLocalDate(),
				episode.getRuntimeMinutes()
			));
		}
		return result;
	}
}
