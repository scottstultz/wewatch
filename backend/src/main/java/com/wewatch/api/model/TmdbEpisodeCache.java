package com.wewatch.api.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "tmdb_episode_cache",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_tmdb_episode_cache", columnNames = {"tmdb_id", "season_number", "episode_number"})
	}
)
public class TmdbEpisodeCache {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tmdb_id", nullable = false, length = 255)
	private String tmdbId;

	@Column(name = "season_number", nullable = false)
	private Integer seasonNumber;

	@Column(name = "episode_number", nullable = false)
	private Integer episodeNumber;

	@Column(name = "name", length = 255)
	private String name;

	@Column(name = "overview", columnDefinition = "TEXT")
	private String overview;

	@Column(name = "air_date")
	private LocalDate airDate;

	@Column(name = "runtime_minutes")
	private Integer runtimeMinutes;

	@Column(name = "still_path", length = 255)
	private String stillPath;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	public TmdbEpisodeCache() {
	}

	public Long getId() { return id; }

	public String getTmdbId() { return tmdbId; }
	public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }

	public Integer getSeasonNumber() { return seasonNumber; }
	public void setSeasonNumber(Integer seasonNumber) { this.seasonNumber = seasonNumber; }

	public Integer getEpisodeNumber() { return episodeNumber; }
	public void setEpisodeNumber(Integer episodeNumber) { this.episodeNumber = episodeNumber; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getOverview() { return overview; }
	public void setOverview(String overview) { this.overview = overview; }

	public LocalDate getAirDate() { return airDate; }
	public void setAirDate(LocalDate airDate) { this.airDate = airDate; }

	public Integer getRuntimeMinutes() { return runtimeMinutes; }
	public void setRuntimeMinutes(Integer runtimeMinutes) { this.runtimeMinutes = runtimeMinutes; }

	public String getStillPath() { return stillPath; }
	public void setStillPath(String stillPath) { this.stillPath = stillPath; }

	public Instant getFetchedAt() { return fetchedAt; }
	public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
