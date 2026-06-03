package com.wewatch.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistMemberId;

public interface WatchlistMemberRepository extends JpaRepository<WatchlistMember, WatchlistMemberId> {

	List<WatchlistMember> findByIdUserId(Long userId);

	List<WatchlistMember> findByIdWatchlistId(Long watchlistId);

	List<WatchlistMember> findByIdWatchlistIdIn(Collection<Long> watchlistIds);

	Optional<WatchlistMember> findByIdWatchlistIdAndRole(Long watchlistId, MemberRole role);
}
