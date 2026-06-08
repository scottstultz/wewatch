package com.wewatch.api.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.BulkSeasonRequest;
import com.wewatch.api.dto.EpisodeProgressResponse;
import com.wewatch.api.model.EpisodeProgress;
import com.wewatch.api.model.User;
import com.wewatch.api.service.EpisodeProgressService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/watchlists/{watchlistId}/entries/{entryId}/episodes")
public class EpisodeProgressController {

	private final EpisodeProgressService episodeProgressService;
	private final WatchlistService watchlistService;

	public EpisodeProgressController(
		EpisodeProgressService episodeProgressService,
		WatchlistService watchlistService
	) {
		this.episodeProgressService = episodeProgressService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	public List<EpisodeProgressResponse> getProgress(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@AuthenticationPrincipal User caller,
		@RequestParam(required = false) Integer season
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return episodeProgressService.getProgress(watchlistId, entryId, season)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	@PatchMapping("/{seasonNumber}/{episodeNumber}")
	public EpisodeProgressResponse toggleEpisode(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@PathVariable int seasonNumber,
		@PathVariable int episodeNumber,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		EpisodeProgress progress = episodeProgressService.toggleEpisode(
			watchlistId, entryId, seasonNumber, episodeNumber
		);
		return toResponse(progress);
	}

	@PutMapping("/{seasonNumber}")
	public List<EpisodeProgressResponse> bulkMarkSeason(
		@PathVariable Long watchlistId,
		@PathVariable Long entryId,
		@PathVariable int seasonNumber,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody BulkSeasonRequest request
	) {
		watchlistService.requireEditor(watchlistId, caller.getId());
		return episodeProgressService.bulkMarkSeason(
			watchlistId, entryId, seasonNumber, request.watched()
		)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	private EpisodeProgressResponse toResponse(EpisodeProgress progress) {
		return new EpisodeProgressResponse(
			progress.getId(),
			progress.getWatchlistEntryId(),
			progress.getSeasonNumber(),
			progress.getEpisodeNumber(),
			progress.getWatched(),
			progress.getWatchedAt()
		);
	}
}
