package com.wewatch.api.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.wewatch.api.dto.EpisodeProgressSummary;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.projection.EpisodeProgressCounts;
import com.wewatch.api.repository.projection.LastWatchedEpisode;
import com.wewatch.api.repository.projection.NextEpisode;

/**
 * Builds per-entry episode progress summaries (watched counts, last watched,
 * next episode, show status) for TV watchlist entries.
 */
@Service
public class EpisodeProgressSummaryService {

	private final EpisodeProgressRepository episodeProgressRepository;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;

	public EpisodeProgressSummaryService(
		EpisodeProgressRepository episodeProgressRepository,
		TmdbTitleCacheRepository tmdbTitleCacheRepository
	) {
		this.episodeProgressRepository = episodeProgressRepository;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
	}

	/**
	 * Builds summaries for the given entries, keyed by entry id. Entries with no
	 * watched episodes are omitted. Caught-up entries (no next episode) are
	 * enriched with the cached TMDB show status via the entry's external id.
	 */
	public Map<Long, EpisodeProgressSummary> buildSummaries(Map<Long, String> externalIdsByEntryId) {
		if (externalIdsByEntryId.isEmpty()) {
			return Collections.emptyMap();
		}
		List<Long> entryIds = new ArrayList<>(externalIdsByEntryId.keySet());

		Map<Long, LastWatchedEpisode> lastWatched = new HashMap<>();
		for (LastWatchedEpisode lw : episodeProgressRepository.findLastWatchedByEntryIds(entryIds)) {
			lastWatched.put(lw.getEntryId(), lw);
		}

		Map<Long, NextEpisode> nextEpisode = new HashMap<>();
		for (NextEpisode next : episodeProgressRepository.findNextEpisodeByEntryIds(entryIds)) {
			nextEpisode.put(next.getEntryId(), next);
		}

		Map<Long, EpisodeProgressSummary> result = new HashMap<>();
		for (EpisodeProgressCounts counts : episodeProgressRepository.summarizeByEntryIds(entryIds)) {
			if (counts.getWatched() == 0) {
				continue;
			}
			LastWatchedEpisode lw = lastWatched.get(counts.getEntryId());
			NextEpisode next = nextEpisode.get(counts.getEntryId());
			result.put(counts.getEntryId(), new EpisodeProgressSummary(
				counts.getWatched(),
				lw != null ? lw.getSeasonNumber() : null,
				lw != null ? lw.getEpisodeNumber() : null,
				next != null ? next.getSeasonNumber() : null,
				next != null ? next.getEpisodeNumber() : null,
				next != null ? next.getName() : null,
				next != null && next.getAirDate() != null ? next.getAirDate().toLocalDate() : null,
				next != null ? next.getRuntimeMinutes() : null,
				null
			));
		}

		enrichCaughtUpWithShowStatus(result, externalIdsByEntryId);
		return result;
	}

	private void enrichCaughtUpWithShowStatus(
		Map<Long, EpisodeProgressSummary> summaries, Map<Long, String> externalIdsByEntryId
	) {
		List<String> caughtUpTmdbIds = summaries.entrySet().stream()
			.filter(e -> e.getValue().nextSeason() == null)
			.map(e -> externalIdsByEntryId.get(e.getKey()))
			.filter(id -> id != null)
			.distinct()
			.collect(Collectors.toList());
		if (caughtUpTmdbIds.isEmpty()) {
			return;
		}
		Map<String, String> statusByTmdbId = new HashMap<>();
		tmdbTitleCacheRepository.findAllById(caughtUpTmdbIds)
			.forEach(c -> statusByTmdbId.put(c.getTmdbId(), c.getStatus()));

		for (Map.Entry<Long, EpisodeProgressSummary> e : summaries.entrySet()) {
			if (e.getValue().nextSeason() != null) {
				continue;
			}
			String tmdbId = externalIdsByEntryId.get(e.getKey());
			String status = tmdbId != null ? statusByTmdbId.get(tmdbId) : null;
			if (status != null) {
				EpisodeProgressSummary old = e.getValue();
				e.setValue(new EpisodeProgressSummary(
					old.watchedCount(), old.lastWatchedSeason(), old.lastWatchedEpisode(),
					old.nextSeason(), old.nextEpisode(), old.nextEpisodeName(),
					old.nextAirDate(), old.nextRuntimeMinutes(), status
				));
			}
		}
	}
}
