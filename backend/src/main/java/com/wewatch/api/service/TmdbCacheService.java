package com.wewatch.api.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;

@Service
public class TmdbCacheService {

	private static final Logger log = LoggerFactory.getLogger(TmdbCacheService.class);

	private final TmdbClient tmdbClient;
	private final TmdbTitleCacheRepository titleCacheRepository;
	private final TmdbEpisodeCacheRepository episodeCacheRepository;
	private final long ttlDays;

	public TmdbCacheService(
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository titleCacheRepository,
		TmdbEpisodeCacheRepository episodeCacheRepository,
		@Value("${tmdb.cache.ttl-days:7}") long ttlDays
	) {
		this.tmdbClient = tmdbClient;
		this.titleCacheRepository = titleCacheRepository;
		this.episodeCacheRepository = episodeCacheRepository;
		this.ttlDays = ttlDays;
	}

	@Transactional
	public List<TmdbTvSeason> getSeasons(String tmdbId) {
		Optional<TmdbTitleCache> cached = titleCacheRepository.findByTmdbId(tmdbId);
		if (cached.isPresent() && !isStale(cached.get().getFetchedAt())) {
			return tmdbClient.getSeasons(tmdbId);
		}
		TmdbTvDetail detail = tmdbClient.getTvDetail(tmdbId);
		upsertTitleCache(tmdbId, detail);
		return detail.seasons() != null ? detail.seasons() : List.of();
	}

	@Transactional
	public TmdbTvSeason getSeasonDetail(String tmdbId, int seasonNumber) {
		List<TmdbEpisodeCache> cachedEpisodes =
			episodeCacheRepository.findByTmdbIdAndSeasonNumber(tmdbId, seasonNumber);

		if (!cachedEpisodes.isEmpty() && !isStale(cachedEpisodes.get(0).getFetchedAt())) {
			return toTmdbTvSeason(seasonNumber, cachedEpisodes);
		}

		TmdbTvSeason season = tmdbClient.getSeasonDetail(tmdbId, seasonNumber);
		upsertEpisodeCache(tmdbId, seasonNumber, season);
		return season;
	}

	public Optional<TmdbTitleCache> getTitleCache(String tmdbId) {
		return titleCacheRepository.findByTmdbId(tmdbId);
	}

	@Async
	public void prewarmShow(String tmdbId) {
		try {
			TmdbTvDetail detail = tmdbClient.getTvDetail(tmdbId);
			upsertTitleCache(tmdbId, detail);
			List<TmdbTvSeason> seasons = detail.seasons() != null ? detail.seasons() : List.of();
			for (TmdbTvSeason season : seasons) {
				if (season.seasonNumber() == 0) continue; // skip specials season
				try {
					TmdbTvSeason full = tmdbClient.getSeasonDetail(tmdbId, season.seasonNumber());
					upsertEpisodeCache(tmdbId, season.seasonNumber(), full);
				} catch (Exception e) {
					log.warn("Failed to prewarm season {}/{}: {}", tmdbId, season.seasonNumber(), e.getMessage());
				}
			}
		} catch (Exception e) {
			log.warn("Failed to prewarm show {}: {}", tmdbId, e.getMessage());
		}
	}

	private boolean isStale(Instant fetchedAt) {
		return fetchedAt.isBefore(Instant.now().minus(ttlDays, ChronoUnit.DAYS));
	}

	private void upsertTitleCache(String tmdbId, TmdbTvDetail detail) {
		TmdbTitleCache row = titleCacheRepository.findByTmdbId(tmdbId).orElse(new TmdbTitleCache());
		row.setTmdbId(tmdbId);
		row.setType("TV");
		row.setName(tmdbId); // name not in TmdbTvDetail — populated from titles table by callers if needed
		row.setStatus(detail.status());
		row.setFirstAirDate(parseDate(detail.firstAirDate()));
		row.setNumberOfSeasons(detail.numberOfSeasons());
		row.setFetchedAt(Instant.now());
		titleCacheRepository.save(row);
	}

	private void upsertEpisodeCache(String tmdbId, int seasonNumber, TmdbTvSeason season) {
		if (season.episodes() == null) return;
		Instant now = Instant.now();
		for (TmdbTvEpisode ep : season.episodes()) {
			TmdbEpisodeCache row = episodeCacheRepository
				.findByTmdbIdAndSeasonNumberAndEpisodeNumber(tmdbId, seasonNumber, ep.episodeNumber())
				.orElse(new TmdbEpisodeCache());
			row.setTmdbId(tmdbId);
			row.setSeasonNumber(seasonNumber);
			row.setEpisodeNumber(ep.episodeNumber());
			row.setName(ep.name());
			row.setOverview(ep.overview());
			row.setAirDate(parseDate(ep.airDate()));
			row.setRuntimeMinutes(ep.runtime());
			row.setStillPath(ep.stillPath());
			row.setFetchedAt(now);
			episodeCacheRepository.save(row);
		}
	}

	private TmdbTvSeason toTmdbTvSeason(int seasonNumber, List<TmdbEpisodeCache> cachedEpisodes) {
		List<TmdbTvEpisode> episodes = cachedEpisodes.stream()
			.sorted((a, b) -> Integer.compare(a.getEpisodeNumber(), b.getEpisodeNumber()))
			.map(ep -> new TmdbTvEpisode(
				0L,
				ep.getEpisodeNumber(),
				ep.getName(),
				ep.getOverview(),
				ep.getAirDate() != null ? ep.getAirDate().toString() : null,
				ep.getStillPath(),
				ep.getRuntimeMinutes()
			))
			.toList();
		return new TmdbTvSeason(0L, seasonNumber, null, null, null, episodes.size(), null, episodes);
	}

	private LocalDate parseDate(String date) {
		if (date == null || date.isBlank()) return null;
		try {
			return LocalDate.parse(date);
		} catch (Exception e) {
			return null;
		}
	}
}
