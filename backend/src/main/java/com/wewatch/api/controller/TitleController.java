package com.wewatch.api.controller;

import java.util.Comparator;
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

import com.wewatch.api.dto.CastMemberResponse;
import com.wewatch.api.dto.EpisodeResponse;
import com.wewatch.api.dto.SeasonDetailResponse;
import com.wewatch.api.dto.SeasonSummaryResponse;
import com.wewatch.api.dto.TitleDetailResponse;
import com.wewatch.api.dto.TitleResolveRequest;
import com.wewatch.api.dto.TitleResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.dto.TitleSearchResults;
import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.service.RecommendationService;
import com.wewatch.api.service.TitleRatingService;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.tmdb.TmdbCastMember;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbCredits;
import com.wewatch.api.tmdb.TmdbDates;
import com.wewatch.api.tmdb.TmdbGenre;
import com.wewatch.api.tmdb.TmdbMovieDetail;
import com.wewatch.api.tmdb.TmdbRegionWatchProviders;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvSeason;
import com.wewatch.api.tmdb.TmdbWatchProviders;
import com.wewatch.api.tmdb.TrailerPicker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/titles")
@Tag(name = "Titles", description = "TMDB-backed title search/detail, local title resolution, and TV season data.")
public class TitleController {

	private static final String TMDB_SOURCE = "TMDB";
	private static final String DEFAULT_WATCH_REGION = "US";
	private static final int CAST_LIMIT = 10;

	private final TitleService titleService;
	private final TmdbClient tmdbClient;
	private final TmdbCacheService tmdbCacheService;
	private final TitleRatingService titleRatingService;
	private final RecommendationService recommendationService;

	public TitleController(
		TitleService titleService,
		TmdbClient tmdbClient,
		TmdbCacheService tmdbCacheService,
		TitleRatingService titleRatingService,
		RecommendationService recommendationService
	) {
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
		this.tmdbCacheService = tmdbCacheService;
		this.titleRatingService = titleRatingService;
		this.recommendationService = recommendationService;
	}

	@GetMapping("/search")
	@Operation(summary = "Search titles (and people) via TMDB",
		description = "Returns matching titles plus, on a multi search (no type filter), person "
			+ "hits ranked by TMDB popularity for the Discover \"People\" row (#356). A movie/tv "
			+ "type filter returns titles only. Blank query returns empty lists without calling TMDB.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Search results returned (possibly empty)"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
	public TitleSearchResults searchTitles(
		@RequestParam String q,
		@RequestParam(required = false) TitleType type
	) {
		if (q.isBlank()) {
			return new TitleSearchResults(List.of(), List.of());
		}
		return tmdbClient.search(q, type);
	}

	@GetMapping("/recommendations")
	@Operation(summary = "Title-anchored \"More Like This\" recommendations",
		description = "Related titles for the given title, from TMDB's own recommendations topped "
			+ "up with `similar` when sparse (#358). Deliberately title-anchored — this answers "
			+ "\"what else is like this one?\" in context and is distinct from the personalized "
			+ "Discover shelves. Cached in-process per (type, id).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Recommendations returned (possibly empty)"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
	public List<TitleSearchResponse> recommendations(
		@RequestParam String externalId,
		@RequestParam TitleType type
	) {
		return recommendationService.recommendationsFor(type, externalId);
	}

	@GetMapping("/detail")
	@Operation(summary = "Get live TMDB detail for a title",
		description = "Includes genres, watch providers resolved for the caller's watch region "
			+ "(#270), the caller's own thumbs rating if the title has been resolved locally "
			+ "(#273), top-billed cast (#295), and a YouTube trailer URL when TMDB has one "
			+ "(#340). This calls TMDB live and does not require the title to exist locally.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Detail returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
	public TitleDetailResponse getTitleDetail(
		@RequestParam String externalId,
		@Parameter(description = "External source of the title. Only \"TMDB\" is supported.")
		@RequestParam String externalSource,
		@RequestParam TitleType type,
		@AuthenticationPrincipal User caller
	) {
		// Providers ride the same TMDB detail call (#270), resolved for the
		// caller's watch region (US default — availability is region-scoped and
		// the page needs some region before the user configures one)
		String watchRegion = caller.getWatchRegion() != null ? caller.getWatchRegion() : DEFAULT_WATCH_REGION;

		// The caller's thumbs rating (#273) rides along when a titles row exists
		// for this external id — pre-resolve titles have no row and no rating
		Long titleId = titleService.findOptionalByExternalSourceAndExternalId(externalSource, externalId)
			.map(Title::getId)
			.orElse(null);
		Rating myRating = titleId != null
			? titleRatingService.ratingsFor(caller.getId(), List.of(titleId)).get(titleId)
			: null;

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
				detail.runtime(),
				null,
				null,
				watchRegion,
				flatrateProviders(detail.watchProviders(), watchRegion),
				titleId,
				myRating,
				topCast(detail.credits()),
				TrailerPicker.trailerUrl(detail.videos())
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
			null,
			seasons.size(),
			seasons,
			watchRegion,
			flatrateProviders(detail.watchProviders(), watchRegion),
			titleId,
			myRating,
			topCast(detail.credits()),
			TrailerPicker.trailerUrl(detail.videos())
		);
	}

	private List<String> genreNames(List<TmdbGenre> genres) {
		return genres != null ? genres.stream().map(TmdbGenre::name).toList() : List.of();
	}

	// Credits already ride the detail call via append_to_response (#269), so the
	// Cast section (#295) adds no TMDB request. TMDB usually returns cast in
	// billing order, but sort defensively — obscure titles come back unordered,
	// and a null order sinks to the bottom.
	private List<CastMemberResponse> topCast(TmdbCredits credits) {
		if (credits == null || credits.cast() == null) return List.of();
		return credits.cast().stream()
			.sorted(Comparator.comparing(TmdbCastMember::order,
				Comparator.nullsLast(Comparator.naturalOrder())))
			.limit(CAST_LIMIT)
			.map(c -> new CastMemberResponse(
				c.id(),
				c.name(),
				c.character(),
				TmdbClient.profileUrl(c.profilePath())))
			.toList();
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
	@Operation(summary = "Resolve or create the local Title row for a TMDB title",
		description = "Idempotent — returns the existing local row if one already exists for the "
			+ "given external id, otherwise fetches the title from TMDB and creates it.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Title resolved"),
		@ApiResponse(responseCode = "400", description = "Validation failed, or externalSource is not "
			+ "\"TMDB\""),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
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
	@Operation(summary = "List local titles, optionally filtered",
		description = "Paginated search over titles that have already been resolved locally "
			+ "(see /resolve). All filter params are optional and combine as an AND.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Page of titles returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public Page<TitleResponse> getTitles(
		@RequestParam(required = false) String externalId,
		@Parameter(description = "External source of the title. Only \"TMDB\" is supported.")
		@RequestParam(required = false) String externalSource,
		@RequestParam(required = false) TitleType type,
		@RequestParam(required = false) String name,
		@PageableDefault(size = 20) Pageable pageable
	) {
		return titleService.findByFilters(externalId, externalSource, type, name, pageable)
			.map(this::toResponse);
	}

	@GetMapping("/{titleId}")
	@Operation(summary = "Get a local title by id")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Title returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "404", description = "No title with this id")
	})
	public TitleResponse getTitle(@PathVariable Long titleId) {
		return toResponse(titleService.findById(titleId));
	}

	@GetMapping("/{titleId}/seasons")
	@Operation(summary = "List TV season summaries for a local title",
		description = "TV titles only.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Season summaries returned"),
		@ApiResponse(responseCode = "400", description = "Title is not a TV show"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "404", description = "No title with this id"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
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
	@Operation(summary = "Get TV season detail including episodes",
		description = "TV titles only.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Season detail returned"),
		@ApiResponse(responseCode = "400", description = "Title is not a TV show"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "404", description = "No title with this id"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
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
