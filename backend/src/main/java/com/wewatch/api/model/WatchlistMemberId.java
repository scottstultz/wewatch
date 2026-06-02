package com.wewatch.api.model;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class WatchlistMemberId implements Serializable {

	@Column(name = "watchlist_id")
	private Long watchlistId;

	@Column(name = "user_id")
	private Long userId;

	public WatchlistMemberId() {
	}

	public WatchlistMemberId(Long watchlistId, Long userId) {
		this.watchlistId = watchlistId;
		this.userId = userId;
	}

	public Long getWatchlistId() {
		return watchlistId;
	}

	public void setWatchlistId(Long watchlistId) {
		this.watchlistId = watchlistId;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof WatchlistMemberId)) return false;
		WatchlistMemberId that = (WatchlistMemberId) o;
		return Objects.equals(watchlistId, that.watchlistId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(watchlistId, userId);
	}
}
