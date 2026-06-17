package com.wewatch.api.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.User;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/suggestions")
public class SuggestionController {

	private final SuggestionService suggestionService;
	private final WatchlistService watchlistService;

	public SuggestionController(SuggestionService suggestionService, WatchlistService watchlistService) {
		this.suggestionService = suggestionService;
		this.watchlistService = watchlistService;
	}

	@GetMapping
	public ResponseEntity<List<SuggestionShelfResponse>> getSuggestions(
		@RequestParam Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		List<TitleSearchResponse> titles = suggestionService.topPicks(watchlistId);
		if (titles.isEmpty()) {
			return ResponseEntity.ok(List.of());
		}
		return ResponseEntity.ok(List.of(new SuggestionShelfResponse("Top picks for you", titles)));
	}
}
