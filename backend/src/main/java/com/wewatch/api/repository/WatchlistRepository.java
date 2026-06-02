package com.wewatch.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wewatch.api.model.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {
}
