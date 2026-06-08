package com.wewatch.api.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.exception.WatchlistMemberAlreadyExistsException;
import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistMemberId;
import com.wewatch.api.model.WatchlistType;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.repository.WatchlistRepository;

@Service
public class WatchlistService {

	private final WatchlistRepository watchlistRepository;
	private final WatchlistMemberRepository watchlistMemberRepository;

	public WatchlistService(
		WatchlistRepository watchlistRepository,
		WatchlistMemberRepository watchlistMemberRepository
	) {
		this.watchlistRepository = watchlistRepository;
		this.watchlistMemberRepository = watchlistMemberRepository;
	}

	@Transactional
	public Watchlist provisionPersonalWatchlist(Long userId, String name) {
		Instant now = Instant.now();

		Watchlist watchlist = new Watchlist();
		watchlist.setName(name);
		watchlist.setType(WatchlistType.PERSONAL);
		watchlist.setCreatedAt(now);
		watchlist.setUpdatedAt(now);
		Watchlist saved = watchlistRepository.save(watchlist);

		WatchlistMemberId memberId = new WatchlistMemberId(saved.getId(), userId);
		WatchlistMember member = new WatchlistMember();
		member.setId(memberId);
		member.setRole(MemberRole.OWNER);
		member.setJoinedAt(now);
		member.setDefault(true);
		watchlistMemberRepository.save(member);

		return saved;
	}

	public List<Watchlist> findByUserId(Long userId) {
		return watchlistRepository.findByMemberUserId(userId);
	}

	@Transactional
	public Watchlist createShared(String name, Long ownerId) {
		Instant now = Instant.now();

		Watchlist watchlist = new Watchlist();
		watchlist.setName(name);
		watchlist.setType(WatchlistType.SHARED);
		watchlist.setCreatedAt(now);
		watchlist.setUpdatedAt(now);
		Watchlist saved = watchlistRepository.save(watchlist);

		WatchlistMemberId memberId = new WatchlistMemberId(saved.getId(), ownerId);
		WatchlistMember member = new WatchlistMember();
		member.setId(memberId);
		member.setRole(MemberRole.OWNER);
		member.setJoinedAt(now);
		watchlistMemberRepository.save(member);

		return saved;
	}

	public Watchlist findById(Long watchlistId) {
		return watchlistRepository.findById(watchlistId)
			.orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + watchlistId));
	}

	@Transactional
	public Watchlist update(Long watchlistId, String name) {
		Watchlist watchlist = findById(watchlistId);
		watchlist.setName(name);
		watchlist.setUpdatedAt(Instant.now());
		return watchlistRepository.save(watchlist);
	}

	@Transactional
	public void delete(Long watchlistId, Long callerUserId) {
		Watchlist watchlist = findById(watchlistId);
		if (watchlist.getType() == WatchlistType.PERSONAL) {
			throw new ForbiddenException("Personal watchlists cannot be deleted");
		}
		requireOwner(watchlistId, callerUserId);
		// DB-level ON DELETE CASCADE handles watchlist_entries and watchlist_members
		watchlistRepository.deleteById(watchlistId);
	}

	public List<WatchlistMember> findMembersByWatchlistId(Long watchlistId) {
		return watchlistMemberRepository.findByIdWatchlistId(watchlistId);
	}

	public List<WatchlistMember> findMembersByWatchlistIds(Collection<Long> watchlistIds) {
		return watchlistMemberRepository.findByIdWatchlistIdIn(watchlistIds);
	}

	@Transactional
	public WatchlistMember addMember(Long watchlistId, Long userId, Long callerUserId) {
		requireOwner(watchlistId, callerUserId);
		WatchlistMemberId memberId = new WatchlistMemberId(watchlistId, userId);
		if (watchlistMemberRepository.existsById(memberId)) {
			throw new WatchlistMemberAlreadyExistsException(watchlistId, userId);
		}
		WatchlistMember member = new WatchlistMember();
		member.setId(memberId);
		member.setRole(MemberRole.MEMBER);
		member.setJoinedAt(Instant.now());
		return watchlistMemberRepository.save(member);
	}

	@Transactional
	public void removeMember(Long watchlistId, Long targetUserId, Long callerUserId) {
		requireOwner(watchlistId, callerUserId);
		WatchlistMemberId targetId = new WatchlistMemberId(watchlistId, targetUserId);
		WatchlistMember target = watchlistMemberRepository.findById(targetId)
			.orElseThrow(() -> new NoSuchElementException("User " + targetUserId + " is not a member of watchlist " + watchlistId));
		if (target.getRole() == MemberRole.OWNER) {
			throw new ForbiddenException("Cannot remove the owner of a watchlist");
		}
		watchlistMemberRepository.delete(target);
	}

	public WatchlistMember requireMember(Long watchlistId, Long userId) {
		// Validate watchlist exists first so non-members get 404, not 403
		findById(watchlistId);
		return watchlistMemberRepository.findById(new WatchlistMemberId(watchlistId, userId))
			.orElseThrow(() -> new ForbiddenException("Not a member of this watchlist"));
	}

	public void requireOwner(Long watchlistId, Long userId) {
		WatchlistMember member = requireMember(watchlistId, userId);
		if (member.getRole() != MemberRole.OWNER) {
			throw new ForbiddenException("Owner role required");
		}
	}

	@Transactional
	public void setDefault(Long watchlistId, Long userId) {
		requireMember(watchlistId, userId);
		watchlistMemberRepository.clearDefault(userId);
		watchlistMemberRepository.setDefault(watchlistId, userId);
	}
}
