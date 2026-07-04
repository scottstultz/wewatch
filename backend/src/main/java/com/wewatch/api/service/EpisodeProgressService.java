package com.wewatch.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;

@Service
public class EpisodeProgressService {

	private final EpisodeProgressRepository episodeProgressRepository;
	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;
	private final TmdbEpisodeCacheRepository episodeCacheRepository;
	private final Clock clock;

	public EpisodeProgressService(
		EpisodeProgressRepository episodeProgressRepository,
		WatchlistEntryService watchlistEntryService,
		TitleService titleService,
		TmdbEpisodeCacheRepository episodeCacheRepository,
		Clock clock
	) {
		this.episodeProgressRepository = episodeProgressRepository;
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
		this.episodeCacheRepository = episodeCacheRepository;
		this.clock = clock;
	}

	public List<EpisodeProgress> getProgress(Long watchlistId, Long entryId, Integer seasonNumber) {
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		requireTvShow(entry);
		if (seasonNumber != null) {
			return episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(entry.getId(), seasonNumber);
		}
		return episodeProgressRepository.findByWatchlistEntryId(entry.getId());
	}

	@Transactional
	public EpisodeProgress toggleEpisode(Long watchlistId, Long entryId, int seasonNumber, int episodeNumber) {
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		Title title = requireTvShow(entry);

		return episodeProgressRepository
			.findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(entry.getId(), seasonNumber, episodeNumber)
			.map(existing -> {
				boolean newWatched = !existing.getWatched();
				if (newWatched) {
					requireAired(title, seasonNumber, episodeNumber);
				}
				existing.setWatched(newWatched);
				existing.setWatchedAt(newWatched ? Instant.now() : null);
				return episodeProgressRepository.save(existing);
			})
			.orElseGet(() -> {
				requireAired(title, seasonNumber, episodeNumber);
				EpisodeProgress progress = new EpisodeProgress(
					null, entry.getId(), seasonNumber, episodeNumber, true, Instant.now()
				);
				return episodeProgressRepository.save(progress);
			});
	}

	@Transactional
	public List<EpisodeProgress> bulkMarkSeason(
		Long watchlistId, Long entryId, int seasonNumber, boolean watched, List<Integer> episodeNumbers
	) {
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		Title title = requireTvShow(entry);

		List<Integer> targetEpisodes = episodeNumbers;
		if (watched) {
			Set<Integer> unaired = unairedEpisodeNumbers(title, seasonNumber);
			targetEpisodes = episodeNumbers.stream()
				.filter(epNum -> !unaired.contains(epNum))
				.toList();
		}

		// Create rows for episodes that don't have progress yet
		Set<Integer> existing = episodeProgressRepository
			.findByWatchlistEntryIdAndSeasonNumber(entry.getId(), seasonNumber).stream()
			.map(EpisodeProgress::getEpisodeNumber)
			.collect(Collectors.toSet());
		List<EpisodeProgress> missing = targetEpisodes.stream()
			.filter(epNum -> !existing.contains(epNum))
			.map(epNum -> new EpisodeProgress(null, entry.getId(), seasonNumber, epNum, false, null))
			.toList();
		if (!missing.isEmpty()) {
			episodeProgressRepository.saveAll(missing);
		}

		if (watched) {
			if (!targetEpisodes.isEmpty()) {
				episodeProgressRepository.updateEpisodesWatched(
					entry.getId(), seasonNumber, targetEpisodes, true, Instant.now()
				);
			}
		} else {
			episodeProgressRepository.updateSeasonWatched(entry.getId(), seasonNumber, false, null);
		}
		return episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(entry.getId(), seasonNumber);
	}

	private Title requireTvShow(WatchlistEntry entry) {
		Title title = titleService.findById(entry.getTitleId());
		if (title.getType() != TitleType.TV) {
			throw new IllegalArgumentException("Episode tracking is only available for TV shows");
		}
		return title;
	}

	private void requireAired(Title title, int seasonNumber, int episodeNumber) {
		if (unairedEpisodeNumbers(title, seasonNumber).contains(episodeNumber)) {
			throw new IllegalArgumentException("Episode has not aired yet");
		}
	}

	// Episodes with a null air date (specials, sparse TMDB data) or no cache row are
	// treated as aired — the guard only blocks episodes the cache knows are in the future.
	private Set<Integer> unairedEpisodeNumbers(Title title, int seasonNumber) {
		LocalDate today = LocalDate.now(clock);
		return episodeCacheRepository.findByTmdbIdAndSeasonNumber(title.getExternalId(), seasonNumber).stream()
			.filter(ep -> ep.getAirDate() != null && ep.getAirDate().isAfter(today))
			.map(TmdbEpisodeCache::getEpisodeNumber)
			.collect(Collectors.toSet());
	}
}
