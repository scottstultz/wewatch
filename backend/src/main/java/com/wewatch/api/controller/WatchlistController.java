package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.AddMemberRequest;
import com.wewatch.api.dto.WatchlistCreateRequest;
import com.wewatch.api.dto.WatchlistMemberResponse;
import com.wewatch.api.dto.WatchlistResponse;
import com.wewatch.api.dto.WatchlistUpdateRequest;
import com.wewatch.api.model.User;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

@RestController
@RequestMapping("/api/watchlists")
public class WatchlistController {

	private final WatchlistService watchlistService;
	private final UserService userService;

	public WatchlistController(WatchlistService watchlistService, UserService userService) {
		this.watchlistService = watchlistService;
		this.userService = userService;
	}

	@GetMapping
	public List<WatchlistResponse> getWatchlists(@AuthenticationPrincipal User caller) {
		List<Watchlist> watchlists = watchlistService.findByUserId(caller.getId());
		if (watchlists.isEmpty()) {
			return List.of();
		}
		List<Long> watchlistIds = watchlists.stream().map(Watchlist::getId).toList();
		List<WatchlistMember> allMembers = watchlistService.findMembersByWatchlistIds(watchlistIds);
		Map<Long, User> usersById = userService.findByIds(
			allMembers.stream().map(m -> m.getId().getUserId()).distinct().collect(Collectors.toList())
		);
		Map<Long, List<WatchlistMember>> membersByWatchlistId = allMembers.stream()
			.collect(Collectors.groupingBy(m -> m.getId().getWatchlistId()));

		// Build a set of watchlist IDs where the caller has is_default = true
		Set<Long> callerDefaults = allMembers.stream()
			.filter(m -> m.getId().getUserId().equals(caller.getId()) && m.isDefault())
			.map(m -> m.getId().getWatchlistId())
			.collect(Collectors.toSet());

		return watchlists.stream()
			.map(w -> toWatchlistResponse(
				w,
				membersByWatchlistId.getOrDefault(w.getId(), List.of()),
				usersById,
				callerDefaults.contains(w.getId())
			))
			.toList();
	}

	@PostMapping
	public ResponseEntity<WatchlistResponse> createWatchlist(
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody WatchlistCreateRequest request
	) {
		Watchlist created = watchlistService.createShared(request.name(), caller.getId());
		WatchlistResponse response = toWatchlistResponse(created, caller.getId());
		return ResponseEntity
			.created(URI.create("/api/watchlists/" + created.getId()))
			.body(response);
	}

	@GetMapping("/{watchlistId}")
	public WatchlistResponse getWatchlist(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return toWatchlistResponse(watchlistService.findById(watchlistId), caller.getId());
	}

	@PatchMapping("/{watchlistId}")
	public WatchlistResponse updateWatchlist(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody WatchlistUpdateRequest request
	) {
		watchlistService.requireOwner(watchlistId, caller.getId());
		Watchlist updated = watchlistService.update(watchlistId, request.name());
		return toWatchlistResponse(updated, caller.getId());
	}

	@DeleteMapping("/{watchlistId}")
	public ResponseEntity<Void> deleteWatchlist(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.delete(watchlistId, caller.getId());
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{watchlistId}/default")
	public WatchlistResponse setDefault(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.setDefault(watchlistId, caller.getId());
		return toWatchlistResponse(watchlistService.findById(watchlistId), caller.getId());
	}

	@PostMapping("/{watchlistId}/members")
	public ResponseEntity<WatchlistMemberResponse> addMember(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody AddMemberRequest request
	) {
		User targetUser = userService.findByEmail(request.email());
		WatchlistMember member = watchlistService.addMember(watchlistId, targetUser.getId(), caller.getId());
		WatchlistMemberResponse response = toMemberResponse(member);
		return ResponseEntity
			.created(URI.create("/api/watchlists/" + watchlistId + "/members/" + member.getId().getUserId()))
			.body(response);
	}

	@DeleteMapping("/{watchlistId}/members/{userId}")
	public ResponseEntity<Void> removeMember(
		@PathVariable Long watchlistId,
		@PathVariable Long userId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.removeMember(watchlistId, userId, caller.getId());
		return ResponseEntity.noContent().build();
	}

	private WatchlistResponse toWatchlistResponse(Watchlist watchlist, Long callerUserId) {
		List<WatchlistMember> members = watchlistService.findMembersByWatchlistId(watchlist.getId());
		Map<Long, User> usersById = userService.findByIds(
			members.stream().map(m -> m.getId().getUserId()).collect(Collectors.toList())
		);
		boolean isDefault = members.stream()
			.anyMatch(m -> m.getId().getUserId().equals(callerUserId) && m.isDefault());
		return toWatchlistResponse(watchlist, members, usersById, isDefault);
	}

	private WatchlistResponse toWatchlistResponse(Watchlist watchlist, List<WatchlistMember> members, Map<Long, User> usersById, boolean isDefault) {
		List<WatchlistMemberResponse> memberResponses = members.stream()
			.map(m -> toMemberResponse(m, usersById.get(m.getId().getUserId())))
			.toList();
		return new WatchlistResponse(
			watchlist.getId(),
			watchlist.getName(),
			watchlist.getType(),
			watchlist.getCreatedAt(),
			watchlist.getUpdatedAt(),
			memberResponses,
			isDefault
		);
	}

	private WatchlistMemberResponse toMemberResponse(WatchlistMember member) {
		User user = userService.findById(member.getId().getUserId());
		return toMemberResponse(member, user);
	}

	private WatchlistMemberResponse toMemberResponse(WatchlistMember member, User user) {
		return new WatchlistMemberResponse(
			user.getId(),
			user.getEmail(),
			user.getDisplayName(),
			member.getRole(),
			member.getJoinedAt()
		);
	}
}
