package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.EpisodeResponse;
import com.wewatch.api.dto.SeasonDetailResponse;
import com.wewatch.api.dto.SeasonSummaryResponse;
import com.wewatch.api.dto.TitleCreateRequest;
import com.wewatch.api.dto.TitleResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.dto.TitleUpdateRequest;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbTvSeason;

@RestController
@RequestMapping("/api/titles")
public class TitleController {

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

	@PostMapping
	public ResponseEntity<TitleResponse> createTitle(@Valid @RequestBody TitleCreateRequest request) {
		Title createdTitle = titleService.create(new Title(
			null,
			request.externalId(),
			request.externalSource(),
			request.type(),
			request.name(),
			request.overview(),
			request.releaseDate(),
			request.posterUrl(),
			null,
			null
		));

		return ResponseEntity
			.created(URI.create("/api/titles/" + createdTitle.getId()))
			.body(toResponse(createdTitle));
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

	@PatchMapping("/{titleId}")
	public TitleResponse updateTitle(@PathVariable Long titleId, @Valid @RequestBody TitleUpdateRequest request) {
		return toResponse(titleService.update(
			titleId,
			request.name(),
			request.overview(),
			request.releaseDate(),
			request.posterUrl(),
			request.type()
		));
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
