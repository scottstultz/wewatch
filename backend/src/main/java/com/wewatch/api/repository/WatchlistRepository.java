package com.wewatch.api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

	@Query("SELECT w FROM Watchlist w WHERE w.id IN "
		+ "(SELECT m.id.watchlistId FROM WatchlistMember m WHERE m.id.userId = :userId) "
		+ "ORDER BY w.createdAt DESC")
	List<Watchlist> findByMemberUserId(@Param("userId") Long userId);
}
