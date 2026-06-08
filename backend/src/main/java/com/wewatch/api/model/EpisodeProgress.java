package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(
	name = "episode_progress",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uq_episode_progress_entry_season_episode",
			columnNames = {"watchlist_entry_id", "season_number", "episode_number"}
		)
	}
)
public class EpisodeProgress {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotNull
	@Column(name = "watchlist_entry_id", nullable = false)
	private Long watchlistEntryId;

	@NotNull
	@Column(name = "season_number", nullable = false)
	private Integer seasonNumber;

	@NotNull
	@Column(name = "episode_number", nullable = false)
	private Integer episodeNumber;

	@NotNull
	@Column(name = "watched", nullable = false)
	private Boolean watched = false;

	@Column(name = "watched_at")
	private Instant watchedAt;

	public EpisodeProgress() {
	}

	public EpisodeProgress(Long id, Long watchlistEntryId, int seasonNumber, int episodeNumber, boolean watched, Instant watchedAt) {
		this.id = id;
		this.watchlistEntryId = watchlistEntryId;
		this.seasonNumber = seasonNumber;
		this.episodeNumber = episodeNumber;
		this.watched = watched;
		this.watchedAt = watchedAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getWatchlistEntryId() {
		return watchlistEntryId;
	}

	public void setWatchlistEntryId(Long watchlistEntryId) {
		this.watchlistEntryId = watchlistEntryId;
	}

	public Integer getSeasonNumber() {
		return seasonNumber;
	}

	public void setSeasonNumber(Integer seasonNumber) {
		this.seasonNumber = seasonNumber;
	}

	public Integer getEpisodeNumber() {
		return episodeNumber;
	}

	public void setEpisodeNumber(Integer episodeNumber) {
		this.episodeNumber = episodeNumber;
	}

	public Boolean getWatched() {
		return watched;
	}

	public void setWatched(Boolean watched) {
		this.watched = watched;
	}

	public Instant getWatchedAt() {
		return watchedAt;
	}

	public void setWatchedAt(Instant watchedAt) {
		this.watchedAt = watchedAt;
	}
}
