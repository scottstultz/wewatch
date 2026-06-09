package com.wewatch.api.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tmdb_title_cache")
public class TmdbTitleCache {

	@Id
	@Column(name = "tmdb_id", length = 255)
	private String tmdbId;

	@Column(name = "type", nullable = false, length = 32)
	private String type;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "overview", columnDefinition = "TEXT")
	private String overview;

	@Column(name = "poster_path", length = 255)
	private String posterPath;

	@Column(name = "status", length = 100)
	private String status;

	@Column(name = "first_air_date")
	private LocalDate firstAirDate;

	@Column(name = "number_of_seasons")
	private Integer numberOfSeasons;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	public TmdbTitleCache() {
	}

	public String getTmdbId() { return tmdbId; }
	public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }

	public String getType() { return type; }
	public void setType(String type) { this.type = type; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getOverview() { return overview; }
	public void setOverview(String overview) { this.overview = overview; }

	public String getPosterPath() { return posterPath; }
	public void setPosterPath(String posterPath) { this.posterPath = posterPath; }

	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }

	public LocalDate getFirstAirDate() { return firstAirDate; }
	public void setFirstAirDate(LocalDate firstAirDate) { this.firstAirDate = firstAirDate; }

	public Integer getNumberOfSeasons() { return numberOfSeasons; }
	public void setNumberOfSeasons(Integer numberOfSeasons) { this.numberOfSeasons = numberOfSeasons; }

	public Instant getFetchedAt() { return fetchedAt; }
	public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
