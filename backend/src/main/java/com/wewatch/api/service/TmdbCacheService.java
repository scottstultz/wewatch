package com.wewatch.api.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TmdbEpisodeCache;
import com.wewatch.api.model.TmdbSeasonCache;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TmdbEpisodeCacheRepository;
import com.wewatch.api.repository.TmdbSeasonCacheRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.model.CachedKeyword;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.tmdb.TmdbCastMember;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbCollectionRef;
import com.wewatch.api.tmdb.TmdbCredits;
import com.wewatch.api.tmdb.TmdbDates;
import com.wewatch.api.tmdb.TmdbGenre;
import com.wewatch.api.tmdb.TmdbKeyword;
import com.wewatch.api.tmdb.TmdbMovieDetail;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;
import com.wewatch.api.tmdb.TmdbWatchProvider;
import com.wewatch.api.tmdb.TmdbWatchProviders;

@Service
public class TmdbCacheService {

	private static final Logger log = LoggerFactory.getLogger(TmdbCacheService.class);

	// Top-billed cast kept per title (#269): enough to catch the leads a user
	// follows without turning every ensemble piece into a people-graph hub
	private static final int TOP_CAST_LIMIT = 5;

	private final TmdbClient tmdbClient;
	private final TmdbTitleCacheRepository titleCacheRepository;
	private final TmdbSeasonCacheRepository seasonCacheRepository;
	private final TmdbEpisodeCacheRepository episodeCacheRepository;
	private final long ttlDays;

	public TmdbCacheService(
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository titleCacheRepository,
		TmdbSeasonCacheRepository seasonCacheRepository,
		TmdbEpisodeCacheRepository episodeCacheRepository,
		@Value("${tmdb.cache.ttl-days:7}") long ttlDays
	) {
		this.tmdbClient = tmdbClient;
		this.titleCacheRepository = titleCacheRepository;
		this.seasonCacheRepository = seasonCacheRepository;
		this.episodeCacheRepository = episodeCacheRepository;
		this.ttlDays = ttlDays;
	}

	@Transactional
	public List<TmdbTvSeason> getSeasons(String tmdbId) {
		List<TmdbSeasonCache> cachedSeasons = seasonCacheRepository.findByTmdbId(tmdbId);
		if (!cachedSeasons.isEmpty() && !isStale(cachedSeasons.get(0).getFetchedAt())) {
			return realSeasons(toTvSeasons(cachedSeasons));
		}
		TmdbTvDetail detail = tmdbClient.getTvDetail(tmdbId);
		upsertTvCache(tmdbId, detail);
		return realSeasons(detail.seasons());
	}

	// Exclude season 0 ("Specials") so every page agrees on what counts as a season.
	private List<TmdbTvSeason> realSeasons(List<TmdbTvSeason> seasons) {
		if (seasons == null) return List.of();
		return seasons.stream().filter(s -> s.seasonNumber() > 0).toList();
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

	/**
	 * Cached TMDB genre ids for a batch of titles, keyed by title id (#381).
	 *
	 * <p>One {@code findAllById} for the whole collection, so a page of watchlist entries costs a
	 * single cache read rather than one per entry. Callers with a single title pass a one-element
	 * collection and take the same path.
	 *
	 * <p>A title with no cache row yet — or a row cached before its genres were populated — maps to
	 * an empty list, never null: "we don't know this title's genres" is not an error, and a caller
	 * filtering on genre wants to skip it, not to fail.
	 */
	public Map<Long, List<Integer>> genreIdsByTitleId(Collection<Title> titles) {
		List<String> externalIds = titles.stream()
			.filter(t -> t != null && t.getExternalId() != null)
			.map(Title::getExternalId)
			.distinct()
			.toList();
		if (externalIds.isEmpty()) return Map.of();

		Map<String, TmdbTitleCache> cacheById = titleCacheRepository.findAllById(externalIds).stream()
			.collect(Collectors.toMap(TmdbTitleCache::getTmdbId, Function.identity()));

		Map<Long, List<Integer>> byTitleId = new LinkedHashMap<>();
		for (Title title : titles) {
			if (title == null || title.getId() == null) continue;
			byTitleId.put(title.getId(), genreIdsOf(cacheById.get(title.getExternalId()), title.getType()));
		}
		return byTitleId;
	}

	private List<Integer> genreIdsOf(TmdbTitleCache row, TitleType type) {
		if (row == null || row.getGenreIds() == null) return List.of();
		// tmdb_title_cache's key is a bare TMDB id with no medium namespace (V9), so movie 1399 and
		// TV 1399 share one row and whichever prewarm ran last owns it. Genre ids are medium-specific:
		// handing a movie the TV row's 10759 would label it "Action & Adventure", a genre that does
		// not exist for movies. Fail closed to no genres rather than to wrong ones.
		if (type == null || !type.name().equals(row.getType())) return List.of();
		return List.copyOf(row.getGenreIds());
	}

	@Async
	public void prewarmShow(String tmdbId) {
		try {
			TmdbTvDetail detail = tmdbClient.getTvDetail(tmdbId);
			upsertTvCache(tmdbId, detail);
			cacheKeywords(TitleType.TV, tmdbId);
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

	@Async
	public void prewarmMovie(String tmdbId) {
		try {
			TmdbMovieDetail detail = tmdbClient.getMovieDetail(tmdbId);
			upsertMovieCache(tmdbId, detail);
			cacheKeywords(TitleType.MOVIE, tmdbId);
		} catch (Exception e) {
			log.warn("Failed to prewarm movie {}: {}", tmdbId, e.getMessage());
		}
	}

	private void cacheKeywords(TitleType type, String tmdbId) {
		try {
			List<TmdbKeyword> kws = tmdbClient.getKeywords(type, tmdbId);
			titleCacheRepository.findByTmdbId(tmdbId).ifPresent(row -> {
				row.setKeywordIds(kws.stream().map(TmdbKeyword::id).toList());
				// Names ride along for keyword-seeded shelf labels (#271); the id
				// CSV above stays the scoring path
				row.setKeywords(kws.stream().map(k -> new CachedKeyword(k.id(), k.name())).toList());
				titleCacheRepository.save(row);
			});
		} catch (Exception e) {
			log.warn("Failed to cache keywords for {}: {}", tmdbId, e.getMessage());
		}
	}

	private boolean isStale(Instant fetchedAt) {
		return fetchedAt.isBefore(Instant.now().minus(ttlDays, ChronoUnit.DAYS));
	}

	private void upsertTvCache(String tmdbId, TmdbTvDetail detail) {
		TmdbTitleCache row = titleCacheRepository.findByTmdbId(tmdbId).orElse(new TmdbTitleCache());
		row.setTmdbId(tmdbId);
		row.setType("TV");
		row.setName(detail.name() != null ? detail.name() : tmdbId);
		row.setOverview(detail.overview());
		row.setPosterPath(detail.posterPath());
		row.setStatus(detail.status());
		row.setFirstAirDate(TmdbDates.parse(detail.firstAirDate()));
		row.setNumberOfSeasons(detail.numberOfSeasons());
		if (detail.genres() != null) {
			row.setGenreIds(detail.genres().stream().map(TmdbGenre::id).toList());
		}
		row.setVoteCount(detail.voteCount());
		applyCredits(row, detail.credits());
		applyWatchProviders(row, detail.watchProviders());
		row.setFetchedAt(Instant.now());
		titleCacheRepository.save(row);
		upsertSeasonCache(tmdbId, detail.seasons());
	}

	private void upsertSeasonCache(String tmdbId, List<TmdbTvSeason> seasons) {
		if (seasons == null) return;
		Instant now = Instant.now();
		Map<Integer, TmdbSeasonCache> existing = seasonCacheRepository.findByTmdbId(tmdbId).stream()
			.collect(Collectors.toMap(TmdbSeasonCache::getSeasonNumber, Function.identity()));
		List<TmdbSeasonCache> rows = new ArrayList<>();
		for (TmdbTvSeason season : seasons) {
			TmdbSeasonCache row = existing.getOrDefault(season.seasonNumber(), new TmdbSeasonCache());
			row.setTmdbId(tmdbId);
			row.setSeasonNumber(season.seasonNumber());
			row.setName(season.name());
			row.setOverview(season.overview());
			row.setPosterPath(season.posterPath());
			row.setEpisodeCount(season.episodeCount());
			row.setAirDate(TmdbDates.parse(season.airDate()));
			row.setFetchedAt(now);
			rows.add(row);
		}
		seasonCacheRepository.saveAll(rows);
	}

	private List<TmdbTvSeason> toTvSeasons(List<TmdbSeasonCache> cachedSeasons) {
		return cachedSeasons.stream()
			.sorted((a, b) -> Integer.compare(a.getSeasonNumber(), b.getSeasonNumber()))
			.map(s -> new TmdbTvSeason(
				0L,
				s.getSeasonNumber(),
				s.getName(),
				s.getOverview(),
				s.getPosterPath(),
				s.getEpisodeCount(),
				s.getAirDate() != null ? s.getAirDate().toString() : null,
				null
			))
			.toList();
	}

	private void upsertMovieCache(String tmdbId, TmdbMovieDetail detail) {
		TmdbTitleCache row = titleCacheRepository.findByTmdbId(tmdbId).orElse(new TmdbTitleCache());
		row.setTmdbId(tmdbId);
		row.setType("MOVIE");
		row.setName(detail.title() != null ? detail.title() : tmdbId);
		row.setOverview(detail.overview());
		row.setPosterPath(detail.posterPath());
		row.setStatus(detail.status());
		if (detail.genres() != null) {
			row.setGenreIds(detail.genres().stream().map(TmdbGenre::id).toList());
		}
		row.setVoteCount(detail.voteCount());
		// Stats watch time (#323). TMDB reports "no runtime" as either null or 0;
		// normalize both to null so a zero can't masquerade as a known-zero-minute
		// movie and quietly drag the average down.
		row.setRuntimeMinutes(
			detail.runtime() != null && detail.runtime() > 0 ? detail.runtime() : null);
		applyCredits(row, detail.credits());
		applyWatchProviders(row, detail.watchProviders());
		applyCollection(row, detail.belongsToCollection());
		row.setFetchedAt(Instant.now());
		titleCacheRepository.save(row);
	}

	// Franchise-continuation shelf (#272): belongs_to_collection rides the same
	// movie detail call, no extra request. TV has no collections, so this is
	// only called from upsertMovieCache. A null block (not part of a franchise,
	// or TMDB omission) keeps whatever the row already has, matching credits
	// and watch providers above.
	private void applyCollection(TmdbTitleCache row, TmdbCollectionRef collection) {
		if (collection == null) return;
		row.setCollectionId(collection.id());
		row.setCollectionName(collection.name());
	}

	// Credits arrive on the same detail call via append_to_response (#269). A null
	// credits block (TMDB omission) keeps whatever the row already has, matching
	// how null genres are treated above.
	private void applyCredits(TmdbTitleCache row, TmdbCredits credits) {
		if (credits == null) return;
		if (credits.cast() != null) {
			row.setTopCast(credits.cast().stream()
				.sorted(Comparator.comparing(TmdbCastMember::order,
					Comparator.nullsLast(Comparator.naturalOrder())))
				.limit(TOP_CAST_LIMIT)
				.map(c -> new CachedPerson((int) c.id(), c.name()))
				.toList());
		}
		if (credits.crew() != null) {
			// Distinct by id: multi-part directing credits repeat the same person
			Map<Integer, CachedPerson> directors = new LinkedHashMap<>();
			credits.crew().stream()
				.filter(c -> "Director".equals(c.job()))
				.forEach(c -> directors.putIfAbsent((int) c.id(), new CachedPerson((int) c.id(), c.name())));
			row.setDirectors(List.copyOf(directors.values()));
		}
	}

	// Watch providers arrive on the same detail call via append_to_response
	// (#270). Only flatrate offers are kept — per region, as a compact id list.
	// A null block (TMDB omission) keeps whatever the row already has, matching
	// credits above; a present block with no flatrate offers anywhere stores an
	// empty map ("fetched, streamable nowhere").
	private void applyWatchProviders(TmdbTitleCache row, TmdbWatchProviders providers) {
		if (providers == null || providers.results() == null) return;
		Map<String, List<Integer>> byRegion = new HashMap<>();
		providers.results().forEach((region, offers) -> {
			if (offers != null && offers.flatrate() != null && !offers.flatrate().isEmpty()) {
				byRegion.put(region, offers.flatrate().stream().map(TmdbWatchProvider::providerId).toList());
			}
		});
		row.setWatchProviders(byRegion);
	}

	private void upsertEpisodeCache(String tmdbId, int seasonNumber, TmdbTvSeason season) {
		if (season.episodes() == null) return;
		Instant now = Instant.now();
		Map<Integer, TmdbEpisodeCache> existing =
			episodeCacheRepository.findByTmdbIdAndSeasonNumber(tmdbId, seasonNumber).stream()
				.collect(Collectors.toMap(TmdbEpisodeCache::getEpisodeNumber, Function.identity()));
		List<TmdbEpisodeCache> rows = new ArrayList<>();
		for (TmdbTvEpisode ep : season.episodes()) {
			TmdbEpisodeCache row = existing.getOrDefault(ep.episodeNumber(), new TmdbEpisodeCache());
			row.setTmdbId(tmdbId);
			row.setSeasonNumber(seasonNumber);
			row.setEpisodeNumber(ep.episodeNumber());
			row.setName(ep.name());
			row.setOverview(ep.overview());
			row.setAirDate(TmdbDates.parse(ep.airDate()));
			row.setRuntimeMinutes(ep.runtime());
			row.setStillPath(ep.stillPath());
			row.setFetchedAt(now);
			rows.add(row);
		}
		episodeCacheRepository.saveAll(rows);
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

}
