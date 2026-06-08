package com.wewatch.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistMemberId;

public interface WatchlistMemberRepository extends JpaRepository<WatchlistMember, WatchlistMemberId> {

	List<WatchlistMember> findByIdUserId(Long userId);

	List<WatchlistMember> findByIdWatchlistId(Long watchlistId);

	List<WatchlistMember> findByIdWatchlistIdIn(Collection<Long> watchlistIds);

	Optional<WatchlistMember> findByIdWatchlistIdAndRole(Long watchlistId, MemberRole role);

	@Modifying
	@Query("UPDATE WatchlistMember m SET m.isDefault = false WHERE m.id.userId = :userId AND m.isDefault = true")
	void clearDefault(@Param("userId") Long userId);

	@Modifying
	@Query("UPDATE WatchlistMember m SET m.isDefault = true WHERE m.id.watchlistId = :watchlistId AND m.id.userId = :userId")
	void setDefault(@Param("watchlistId") Long watchlistId, @Param("userId") Long userId);
}
