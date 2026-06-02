package com.wewatch.api.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	public WatchlistService(WatchlistRepository watchlistRepository, WatchlistMemberRepository watchlistMemberRepository) {
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
		watchlistMemberRepository.save(member);

		return saved;
	}
}
