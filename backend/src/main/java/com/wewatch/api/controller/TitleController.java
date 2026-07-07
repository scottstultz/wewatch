package com.wewatch.api.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.EpisodeResponse;
import com.wewatch.api.dto.SeasonDetailResponse;
import com.wewatch.api.dto.SeasonSummaryResponse;
import com.wewatch.api.dto.TitleDetailResponse;
import com.wewatch.api.dto.TitleResolveRequest;
import com.wewatch.api.dto.TitleResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbDates;
import com.wewatch.api.tmdb.TmdbGenre;
import com.wewatch.api.tmdb.TmdbMovieDetail;
import com.wewatch.api.tmdb.TmdbRegionWatchProviders;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvSeason;
import com.wewatch.api.tmdb.TmdbWatchProviders;

@RestController
@RequestMapping("/api/titles")
public class TitleController {

	private static final String TMDB_SOURCE = "TMDB";
	private static final String DEFAULT_WATCH_REGION = "US";

	private final TitleService titleService;
	private final TmdbClient tmdbClient;
	private final TmdbCacheService tmdbCacheService;

	public TitleController(TitleService titleService, TmdbClient tmdbClient, TmdbCacheService tmdbCacheService) {
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
		this.tmdbCacheService = tmdbCacheService;
	}

	@GetMapping("/search")
	public List<TitleSearchResponse> searchTitles(
		@RequestParam String q,
		@RequestParam(required = false) TitleType type
	) {
		if (q.isBlank()) {
			return List.of();
		}
		return tmdbClient.search(q, type);
	}

	@GetMapping("/detail")
	public TitleDetailResponse getTitleDetail(
		@RequestParam String externalId,
		@RequestParam String externalSource,
		@RequestParam TitleType type,
		@AuthenticationPrincipal User caller
	) {
		// Providers ride the same TMDB detail call (#270), resolved for the
		// caller's watch region (US default — availability is region-scoped and
		// the page needs some region before the user configures one)
		String watchRegion = caller.getWatchRegion() != null ? caller.getWatchRegion() : DEFAULT_WATCH_REGION;

		if (type == TitleType.MOVIE) {
			TmdbMovieDetail detail = tmdbClient.getMovieDetail(externalId);
			return new TitleDetailResponse(
				externalId,
				externalSource,
				TitleType.MOVIE,
				detail.title(),
				detail.overview(),
				detail.releaseDate(),
				TmdbClient.posterUrl(detail.posterPath()),
				detail.status(),
				genreNames(detail.genres()),
				detail.voteAverage(),
				detail.voteCount(),
				null,
				null,
				watchRegion,
				flatrateProviders(detail.watchProviders(), watchRegion)
			);
		}

		TmdbTvDetail detail = tmdbClient.getTvDetail(externalId);
		List<SeasonSummaryResponse> seasons = (detail.seasons() != null ? detail.seasons() : List.<TmdbTvSeason>of())
			.stream()
			.filter(s -> s.seasonNumber() > 0) // exclude season 0 ("Specials"), matching TmdbCacheService.getSeasons
			.map(s -> new SeasonSummaryResponse(
				s.seasonNumber(),
				s.name(),
				s.episodeCount() != null ? s.episodeCount() : 0,
				TmdbClient.posterUrl(s.posterPath()),
				s.airDate()
			))
			.toList();
		return new TitleDetailResponse(
			externalId,
			externalSource,
			TitleType.TV,
			detail.name(),
			detail.overview(),
			detail.firstAirDate(),
			TmdbClient.posterUrl(detail.posterPath()),
			detail.status(),
			genreNames(detail.genres()),
			detail.voteAverage(),
			detail.voteCount(),
			seasons.size(),
			seasons,
			watchRegion,
			flatrateProviders(detail.watchProviders(), watchRegion)
		);
	}

	private List<String> genreNames(List<TmdbGenre> genres) {
		return genres != null ? genres.stream().map(TmdbGenre::name).toList() : List.of();
	}

	// Flatrate (subscription) offers only, matching the suggestion pipeline's
	// definition of "streamable" (#270)
	private List<WatchProviderResponse> flatrateProviders(TmdbWatchProviders providers, String region) {
		TmdbRegionWatchProviders offers = providers != null && providers.results() != null
			? providers.results().get(region)
			: null;
		if (offers == null || offers.flatrate() == null) return List.of();
		return offers.flatrate().stream()
			.map(p -> new WatchProviderResponse(
				p.providerId(),
				p.providerName(),
				TmdbClient.providerLogoUrl(p.logoPath()),
				p.displayPriority() != null ? p.displayPriority() : Integer.MAX_VALUE))
			.toList();
	}

	@PostMapping("/resolve")
	public TitleResponse resolveTitle(@Valid @RequestBody TitleResolveRequest request) {
		if (!TMDB_SOURCE.equalsIgnoreCase(request.externalSource())) {
			throw new IllegalArgumentException("Unsupported external source: " + request.externalSource());
		}
		return toResponse(titleService.findOrCreate(
			TMDB_SOURCE,
			request.externalId(),
			() -> fetchTitleFromTmdb(request.externalId(), request.type())
		));
	}

	private Title fetchTitleFromTmdb(String externalId, TitleType type) {
		if (type == TitleType.MOVIE) {
			TmdbMovieDetail detail = tmdbClient.getMovieDetail(externalId);
			return new Title(
				null,
				externalId,
				TMDB_SOURCE,
				TitleType.MOVIE,
				detail.title(),
				detail.overview(),
				TmdbDates.parse(detail.releaseDate()),
				TmdbClient.posterUrl(detail.posterPath()),
				null,
				null
			);
		}
		TmdbTvDetail detail = tmdbClient.getTvDetail(externalId);
		return new Title(
			null,
			externalId,
			TMDB_SOURCE,
			TitleType.TV,
			detail.name(),
			detail.overview(),
			TmdbDates.parse(detail.firstAirDate()),
			TmdbClient.posterUrl(detail.posterPath()),
			null,
			null
		);
	}

	@GetMapping
	public Page<TitleResponse> getTitles(
		@RequestParam(required = false) String externalId,
		@RequestParam(required = false) String externalSource,
		@RequestParam(required = false) TitleType type,
		@RequestParam(required = false) String name,
		@PageableDefault(size = 20) Pageable pageable
	) {
		return titleService.findByFilters(externalId, externalSource, type, name, pageable)
			.map(this::toResponse);
	}

	@GetMapping("/{titleId}")
	public TitleResponse getTitle(@PathVariable Long titleId) {
		return toResponse(titleService.findById(titleId));
	}

	@GetMapping("/{titleId}/seasons")
	public List<SeasonSummaryResponse> getSeasons(@PathVariable Long titleId) {
		Title title = titleService.findById(titleId);
		requireTv(title);
		return tmdbCacheService.getSeasons(title.getExternalId()).stream()
			.map(season -> new SeasonSummaryResponse(
				season.seasonNumber(),
				season.name(),
				season.episodeCount() != null ? season.episodeCount() : 0,
				TmdbClient.posterUrl(season.posterPath()),
				season.airDate()
			))
			.toList();
	}

	@GetMapping("/{titleId}/seasons/{seasonNumber}")
	public SeasonDetailResponse getSeasonDetail(
		@PathVariable Long titleId,
		@PathVariable int seasonNumber
	) {
		Title title = titleService.findById(titleId);
		requireTv(title);
		TmdbTvSeason season = tmdbCacheService.getSeasonDetail(title.getExternalId(), seasonNumber);
		List<EpisodeResponse> episodes = season.episodes() != null
			? season.episodes().stream()
				.map(ep -> new EpisodeResponse(
					ep.episodeNumber(),
					ep.name(),
					ep.overview(),
					ep.airDate(),
					TmdbClient.stillUrl(ep.stillPath()),
					ep.runtime()
				))
				.toList()
			: List.of();
		return new SeasonDetailResponse(
			season.seasonNumber(),
			season.name(),
			season.overview(),
			TmdbClient.posterUrl(season.posterPath()),
			episodes
		);
	}

	private void requireTv(Title title) {
		if (title.getType() != TitleType.TV) {
			throw new IllegalArgumentException("Season data is only available for TV shows");
		}
	}

	private TitleResponse toResponse(Title title) {
		return new TitleResponse(
			title.getId(),
			title.getExternalId(),
			title.getExternalSource(),
			title.getType(),
			title.getName(),
			title.getOverview(),
			title.getReleaseDate(),
			title.getPosterUrl(),
			title.getCreatedAt(),
			title.getUpdatedAt()
		);
	}
}
