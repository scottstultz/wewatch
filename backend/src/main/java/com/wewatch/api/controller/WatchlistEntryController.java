package com.wewatch.api.controller;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.EpisodeProgressSummary;
import com.wewatch.api.dto.WatchlistEntryCreateRequest;
import com.wewatch.api.dto.WatchlistEntryResponse;
import com.wewatch.api.dto.WatchlistEntryUpdateRequest;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.service.EpisodeProgressSummaryService;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.TitleRatingService;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.service.TmdbCacheService.TitleCacheIds;
import com.wewatch.api.service.WatchlistEntryService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/entries")
@Tag(name = "Watchlist Entries", description = "Add, browse, and update titles on a watchlist, including episode-progress tracking.")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;
	private final WatchlistService watchlistService;
	private final EpisodeProgressSummaryService episodeProgressSummaryService;
	private final TmdbCacheService tmdbCacheService;
	private final SuggestionService suggestionService;
	private final TitleRatingService titleRatingService;

	public WatchlistEntryController(
		WatchlistEntryService watchlistEntryService,
		TitleService titleService,
		WatchlistService watchlistService,
		EpisodeProgressSummaryService episodeProgressSummaryService,
		TmdbCacheService tmdbCacheService,
		SuggestionService suggestionService,
		TitleRatingService titleRatingService
	) {
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
		this.watchlistService = watchlistService;
		this.episodeProgressSummaryService = episodeProgressSummaryService;
		this.tmdbCacheService = tmdbCacheService;
		this.suggestionService = suggestionService;
		this.titleRatingService = titleRatingService;
	}

	@PostMapping
	@Operation(summary = "Add a title to a watchlist",
		description = "Editor or owner role required. As side effects, this prewarms the title's TMDB "
			+ "metadata cache (show or movie details) and triggers an async recompute of the watchlist's "
			+ "suggestion shelves.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Entry created"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller does not have editor access to this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist or title not found"),
		@ApiResponse(responseCode = "409", description = "This title is already on the watchlist")
	})
	public ResponseEntity<WatchlistEntryResponse> createWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody WatchlistEntryCreateRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		WatchlistEntry entry = new WatchlistEntry(
			null,
			watchlistId,
			request.titleId(),
			request.status(),
			null,
			null,
			null,
			null
		);
		entry.setAddedByUserId(caller.getId());
		WatchlistEntry created = watchlistEntryService.create(entry);
		Title title = titleService.findById(created.getTitleId());
		if (title.getType() == TitleType.TV) {
			tmdbCacheService.prewarmShow(title.getExternalId());
		} else if (title.getType() == TitleType.MOVIE) {
			tmdbCacheService.prewarmMovie(title.getExternalId());
		}
		suggestionService.recompute(watchlistId);
		return ResponseEntity
			.created(URI.create("/api/watchlists/" + watchlistId + "/entries/" + created.getId()))
			.body(toResponse(created, title, null, callerRating(caller, created.getTitleId()),
				cacheIdsForSingleTitle(title, caller)));
	}

	@GetMapping
	@Operation(summary = "List a watchlist's entries",
		description = "Member-only. Paginated; optionally filtered by watch status. Each entry includes an "
			+ "episode-progress summary for TV titles and the caller's own thumbs rating for the title, if any.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Entries returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public Page<WatchlistEntryResponse> getWatchlistEntries(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@RequestParam(required = false) WatchStatus status,
		@PageableDefault(size = 20) Pageable pageable
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		Page<WatchlistEntry> entries = watchlistEntryService.findByFilters(watchlistId, status, pageable);
		List<Long> titleIds = entries.stream().map(WatchlistEntry::getTitleId).collect(Collectors.toList());
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);

		// Batch-load episode progress summaries for TV entries
		Map<Long, String> tvEntryExternalIds = new HashMap<>();
		entries.stream()
			.filter(e -> {
				Title t = titlesById.get(e.getTitleId());
				return t != null && t.getType() == TitleType.TV;
			})
			.forEach(e -> tvEntryExternalIds.put(e.getId(), titlesById.get(e.getTitleId()).getExternalId()));
		Map<Long, EpisodeProgressSummary> summaries =
			episodeProgressSummaryService.buildSummaries(tvEntryExternalIds);

		// The caller's own ratings (#273) — personal, so keyed on the caller,
		// not the watchlist; one batch read for the page
		Map<Long, Rating> myRatings = titleRatingService.ratingsFor(caller.getId(), titleIds);

		// Genre and provider ids from the title cache (#381, #392) — one batch read for the page,
		// not one per entry
		Map<Long, TitleCacheIds> cacheIds = tmdbCacheService.cacheIdsByTitleId(
			titlesById.values(), watchRegion(caller), caller.getWatchProviderIds());

		return entries.map(e -> toResponse(e, titlesById.get(e.getTitleId()), summaries.get(e.getId()),
			myRatings.get(e.getTitleId()), cacheIds.getOrDefault(e.getTitleId(), TitleCacheIds.EMPTY)));
	}

	@GetMapping("/{entryId}")
	@Operation(summary = "Get a single watchlist entry", description = "Member-only.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Entry returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist or entry not found")
	})
	public WatchlistEntryResponse getWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		Title title = titleService.findById(entry.getTitleId());
		return toResponse(entry, title, summaryForSingleEntry(entry, title),
			callerRating(caller, entry.getTitleId()), cacheIdsForSingleTitle(title, caller));
	}

	@PatchMapping("/{entryId}")
	@Operation(summary = "Update a watchlist entry's watch status",
		description = "Editor or owner role required. Triggers an async recompute of the watchlist's "
			+ "suggestion shelves as a side effect.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Entry updated"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller does not have editor access to this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist or entry not found")
	})
	public WatchlistEntryResponse updateWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId,
		@Valid @RequestBody WatchlistEntryUpdateRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		WatchlistEntry updated = watchlistEntryService.update(watchlistId, entryId, new WatchlistEntry(
			null,
			watchlistId,
			null,
			request.status(),
			null,
			null,
			null,
			null
		));
		// recompute is @Async: it may still race the transaction commit, but it must be
		// invoked after the update so shelves aren't rebuilt from the pre-update status (#198)
		suggestionService.recompute(watchlistId);
		Title title = titleService.findById(updated.getTitleId());
		return toResponse(updated, title, summaryForSingleEntry(updated, title),
			callerRating(caller, updated.getTitleId()), cacheIdsForSingleTitle(title, caller));
	}

	@DeleteMapping("/{entryId}")
	@Operation(summary = "Remove a title from a watchlist",
		description = "Editor or owner role required. Triggers an async recompute of the watchlist's "
			+ "suggestion shelves as a side effect.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Entry removed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller does not have editor access to this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public ResponseEntity<Void> deleteWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		watchlistEntryService.deleteById(watchlistId, entryId);
		suggestionService.recompute(watchlistId);
		return ResponseEntity.noContent().build();
	}

	private EpisodeProgressSummary summaryForSingleEntry(WatchlistEntry entry, Title title) {
		if (title == null || title.getType() != TitleType.TV) {
			return null;
		}
		Map<Long, EpisodeProgressSummary> summaries = episodeProgressSummaryService
			.buildSummaries(Map.of(entry.getId(), title.getExternalId()));
		return summaries.get(entry.getId());
	}

	private Rating callerRating(User caller, Long titleId) {
		return titleRatingService.ratingsFor(caller.getId(), List.of(titleId)).get(titleId);
	}

	// The caller's watch region (#392), defaulting the way TitleController does for
	// /titles/detail — availability is region-scoped and the page needs some region before
	// the user configures one.
	private String watchRegion(User caller) {
		return caller.getWatchRegion() != null ? caller.getWatchRegion() : TitleController.DEFAULT_WATCH_REGION;
	}

	// The single-entry paths take the same batch read as the list, with one title in it
	// (#381, #392). Tolerates a null title, as toResponse already does.
	private TitleCacheIds cacheIdsForSingleTitle(Title title, User caller) {
		if (title == null) return TitleCacheIds.EMPTY;
		return tmdbCacheService.cacheIdsByTitleId(List.of(title), watchRegion(caller), caller.getWatchProviderIds())
			.getOrDefault(title.getId(), TitleCacheIds.EMPTY);
	}

	private WatchlistEntryResponse toResponse(WatchlistEntry entry, Title title, EpisodeProgressSummary summary,
			Rating myRating, TitleCacheIds cacheIds) {
		return new WatchlistEntryResponse(
			entry.getId(),
			entry.getWatchlistId(),
			entry.getAddedByUserId(),
			entry.getTitleId(),
			entry.getExternalId(),
			entry.getExternalSource(),
			title != null ? title.getName() : null,
			title != null ? title.getType() : null,
			title != null ? title.getPosterUrl() : null,
			entry.getStatus(),
			entry.getAddedAt(),
			entry.getUpdatedAt(),
			entry.getStartedAt(),
			entry.getCompletedAt(),
			summary,
			myRating,
			cacheIds.genreIds(),
			cacheIds.providerIds()
		);
	}
}
