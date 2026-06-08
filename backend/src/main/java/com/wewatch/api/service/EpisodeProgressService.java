package com.wewatch.api.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;

@Service
public class EpisodeProgressService {

	private final EpisodeProgressRepository episodeProgressRepository;
	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;

	public EpisodeProgressService(
		EpisodeProgressRepository episodeProgressRepository,
		WatchlistEntryService watchlistEntryService,
		TitleService titleService
	) {
		this.episodeProgressRepository = episodeProgressRepository;
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
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
		requireTvShow(entry);

		return episodeProgressRepository
			.findByWatchlistEntryIdAndSeasonNumberAndEpisodeNumber(entry.getId(), seasonNumber, episodeNumber)
			.map(existing -> {
				boolean newWatched = !existing.getWatched();
				existing.setWatched(newWatched);
				existing.setWatchedAt(newWatched ? Instant.now() : null);
				return episodeProgressRepository.save(existing);
			})
			.orElseGet(() -> {
				EpisodeProgress progress = new EpisodeProgress(
					null, entry.getId(), seasonNumber, episodeNumber, true, Instant.now()
				);
				return episodeProgressRepository.save(progress);
			});
	}

	@Transactional
	public List<EpisodeProgress> bulkMarkSeason(Long watchlistId, Long entryId, int seasonNumber, boolean watched) {
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		requireTvShow(entry);

		Instant watchedAt = watched ? Instant.now() : null;
		episodeProgressRepository.updateSeasonWatched(entry.getId(), seasonNumber, watched, watchedAt);
		return episodeProgressRepository.findByWatchlistEntryIdAndSeasonNumber(entry.getId(), seasonNumber);
	}

	private void requireTvShow(WatchlistEntry entry) {
		Title title = titleService.findById(entry.getTitleId());
		if (title.getType() != TitleType.TV) {
			throw new IllegalArgumentException("Episode tracking is only available for TV shows");
		}
	}
}
