package com.wewatch.api.controller;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.sql.Date;

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
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.EpisodeProgressRepository;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.service.WatchlistEntryService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/entries")
public class WatchlistEntryController {

	private final WatchlistEntryService watchlistEntryService;
	private final TitleService titleService;
	private final WatchlistService watchlistService;
	private final EpisodeProgressRepository episodeProgressRepository;
	private final TmdbCacheService tmdbCacheService;

	public WatchlistEntryController(
		WatchlistEntryService watchlistEntryService,
		TitleService titleService,
		WatchlistService watchlistService,
		EpisodeProgressRepository episodeProgressRepository,
		TmdbCacheService tmdbCacheService
	) {
		this.watchlistEntryService = watchlistEntryService;
		this.titleService = titleService;
		this.watchlistService = watchlistService;
		this.episodeProgressRepository = episodeProgressRepository;
		this.tmdbCacheService = tmdbCacheService;
	}

	@PostMapping
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
		}
		return ResponseEntity
			.created(URI.create("/api/watchlists/" + watchlistId + "/entries/" + created.getId()))
			.body(toResponse(created, title, null));
	}

	@GetMapping
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
		List<Long> tvEntryIds = entries.stream()
			.filter(e -> {
				Title t = titlesById.get(e.getTitleId());
				return t != null && t.getType() == TitleType.TV;
			})
			.map(WatchlistEntry::getId)
			.collect(Collectors.toList());
		Map<Long, EpisodeProgressSummary> summaries = buildSummaries(tvEntryIds);

		return entries.map(e -> toResponse(e, titlesById.get(e.getTitleId()), summaries.get(e.getId())));
	}

	@GetMapping("/{entryId}")
	public WatchlistEntryResponse getWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		WatchlistEntry entry = watchlistEntryService.findById(watchlistId, entryId);
		Title title = titleService.findById(entry.getTitleId());
		EpisodeProgressSummary summary = null;
		if (title != null && title.getType() == TitleType.TV) {
			Map<Long, EpisodeProgressSummary> summaries = buildSummaries(List.of(entry.getId()));
			summary = summaries.get(entry.getId());
		}
		return toResponse(entry, title, summary);
	}

	@PatchMapping("/{entryId}")
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
		Title title = titleService.findById(updated.getTitleId());
		EpisodeProgressSummary summary = null;
		if (title != null && title.getType() == TitleType.TV) {
			Map<Long, EpisodeProgressSummary> summaries = buildSummaries(List.of(updated.getId()));
			summary = summaries.get(updated.getId());
		}
		return toResponse(updated, title, summary);
	}

	@DeleteMapping("/{entryId}")
	public ResponseEntity<Void> deleteWatchlistEntry(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@PathVariable Long entryId
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		watchlistEntryService.deleteById(watchlistId, entryId);
		return ResponseEntity.noContent().build();
	}

	private Map<Long, EpisodeProgressSummary> buildSummaries(List<Long> entryIds) {
		if (entryIds.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<Long, long[]> counts = new HashMap<>();
		for (Object[] row : episodeProgressRepository.summarizeByEntryIds(entryIds)) {
			Long entryId = (Long) row[0];
			long total = (Long) row[1];
			long watched = (Long) row[2];
			counts.put(entryId, new long[] { total, watched });
		}

		Map<Long, int[]> lastWatched = new HashMap<>();
		for (Object[] row : episodeProgressRepository.findLastWatchedByEntryIds(entryIds)) {
			Long entryId = (Long) row[0];
			int season = (Integer) row[1];
			int episode = (Integer) row[2];
			lastWatched.put(entryId, new int[] { season, episode });
		}

		Map<Long, Object[]> nextEpisode = new HashMap<>();
		for (Object[] row : episodeProgressRepository.findNextEpisodeByEntryIds(entryIds)) {
			Long entryId = ((Number) row[0]).longValue();
			nextEpisode.put(entryId, row);
		}

		Map<Long, EpisodeProgressSummary> result = new HashMap<>();
		for (Map.Entry<Long, long[]> e : counts.entrySet()) {
			long watched = e.getValue()[1];
			if (watched == 0) continue;
			int[] lw = lastWatched.get(e.getKey());
			Object[] next = nextEpisode.get(e.getKey());
			result.put(e.getKey(), new EpisodeProgressSummary(
				watched,
				lw != null ? lw[0] : null,
				lw != null ? lw[1] : null,
				next != null ? ((Number) next[1]).intValue() : null,
				next != null ? ((Number) next[2]).intValue() : null,
				next != null ? (String) next[3] : null,
				next != null && next[4] != null
					? ((Date) next[4]).toLocalDate() : null,
				next != null && next[5] != null
					? ((Number) next[5]).intValue() : null
			));
		}
		return result;
	}

	private WatchlistEntryResponse toResponse(WatchlistEntry entry, Title title, EpisodeProgressSummary summary) {
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
			summary
		);
	}
}
