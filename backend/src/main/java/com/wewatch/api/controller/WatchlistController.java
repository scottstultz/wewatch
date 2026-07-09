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
import com.wewatch.api.dto.UpdateMemberRoleRequest;
import com.wewatch.api.dto.WatchlistCreateRequest;
import com.wewatch.api.dto.WatchlistMemberResponse;
import com.wewatch.api.dto.WatchlistResponse;
import com.wewatch.api.dto.WatchlistUpdateRequest;
import com.wewatch.api.model.User;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.service.UserService;
import com.wewatch.api.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlists")
@Tag(name = "Watchlists", description = "Create, manage, and share watchlists (personal and shared) and their memberships.")
public class WatchlistController {

	private final WatchlistService watchlistService;
	private final UserService userService;

	public WatchlistController(WatchlistService watchlistService, UserService userService) {
		this.watchlistService = watchlistService;
		this.userService = userService;
	}

	@GetMapping
	@Operation(summary = "List the caller's watchlists",
		description = "Returns every watchlist (personal and shared) the caller is a member of.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Watchlists returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
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
	@Operation(summary = "Create a shared watchlist", description = "The caller becomes the watchlist's owner.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Watchlist created"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
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
	@Operation(summary = "Get a watchlist", description = "Caller must be a member of the watchlist.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Watchlist returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public WatchlistResponse getWatchlist(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.requireMember(watchlistId, caller.getId());
		return toWatchlistResponse(watchlistService.findById(watchlistId), caller.getId());
	}

	@PatchMapping("/{watchlistId}")
	@Operation(summary = "Rename a watchlist", description = "Owner-only.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Watchlist renamed"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not the watchlist owner"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
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
	@Operation(summary = "Delete a shared watchlist",
		description = "Owner-only. Personal watchlists cannot be deleted. Cascades to the watchlist's "
			+ "entries and memberships.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Watchlist deleted"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403",
			description = "Caller is not the watchlist owner, or the watchlist is a personal watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public ResponseEntity<Void> deleteWatchlist(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.delete(watchlistId, caller.getId());
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{watchlistId}/default")
	@Operation(summary = "Mark a watchlist as the caller's default",
		description = "Member-only. Clears the default flag on the caller's other watchlists.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Default watchlist updated"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not a member of this watchlist"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found")
	})
	public WatchlistResponse setDefault(
		@PathVariable Long watchlistId,
		@AuthenticationPrincipal User caller
	) {
		watchlistService.setDefault(watchlistId, caller.getId());
		return toWatchlistResponse(watchlistService.findById(watchlistId), caller.getId());
	}

	@PostMapping("/{watchlistId}/members")
	@Operation(summary = "Add a member to a watchlist by email",
		description = "Owner-only. The new member is added with the editor role.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Member added"),
		@ApiResponse(responseCode = "400", description = "Validation failed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403", description = "Caller is not the watchlist owner"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found, or no user exists with that email"),
		@ApiResponse(responseCode = "409", description = "That user is already a member of this watchlist")
	})
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

	@PatchMapping("/{watchlistId}/members/{userId}/role")
	@Operation(summary = "Change a member's role",
		description = "Owner-only. The caller cannot change their own role, and the owner's role cannot be "
			+ "changed or assigned to another member (promoting a member to owner is not supported).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Member role updated"),
		@ApiResponse(responseCode = "400", description = "Requested role is OWNER, which is not assignable"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403",
			description = "Caller is not the watchlist owner, is targeting their own membership, or is "
				+ "targeting the owner's membership"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found, or target user is not a member")
	})
	public WatchlistMemberResponse updateMemberRole(
		@PathVariable Long watchlistId,
		@PathVariable Long userId,
		@AuthenticationPrincipal User caller,
		@Valid @RequestBody UpdateMemberRoleRequest request
	) {
		WatchlistMember updated = watchlistService.updateMemberRole(
			watchlistId, userId, request.role(), caller.getId()
		);
		User targetUser = userService.findById(userId);
		return toMemberResponse(updated, targetUser);
	}

	@DeleteMapping("/{watchlistId}/members/{userId}")
	@Operation(summary = "Remove a member from a watchlist", description = "Owner-only. The owner cannot be removed.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Member removed"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "403",
			description = "Caller is not the watchlist owner, or the target is the owner"),
		@ApiResponse(responseCode = "404", description = "Watchlist not found, or target user is not a member")
	})
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
