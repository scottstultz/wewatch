package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "suggestion_impressions",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_suggestion_impressions_watchlist_tmdb", columnNames = {"watchlist_id", "tmdb_id"})
	}
)
public class SuggestionImpression {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "watchlist_id", nullable = false)
	private Long watchlistId;

	@Column(name = "tmdb_id", nullable = false, length = 255)
	private String tmdbId;

	@Column(name = "last_shown_at", nullable = false)
	private Instant lastShownAt;

	public SuggestionImpression() {
	}

	public Long getId() { return id; }

	public Long getWatchlistId() { return watchlistId; }
	public void setWatchlistId(Long watchlistId) { this.watchlistId = watchlistId; }

	public String getTmdbId() { return tmdbId; }
	public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }

	public Instant getLastShownAt() { return lastShownAt; }
	public void setLastShownAt(Instant lastShownAt) { this.lastShownAt = lastShownAt; }
}
