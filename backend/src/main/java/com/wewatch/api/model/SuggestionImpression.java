package com.wewatch.api.model;

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
	name = "suggestion_impressions",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_suggestion_impressions_user_tmdb_day", columnNames = {"user_id", "tmdb_id", "shown_on"})
	}
)
public class SuggestionImpression {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "tmdb_id", nullable = false, length = 255)
	private String tmdbId;

	@Column(name = "shown_on", nullable = false)
	private LocalDate shownOn;

	public SuggestionImpression() {
	}

	public Long getId() { return id; }

	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }

	public String getTmdbId() { return tmdbId; }
	public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }

	public LocalDate getShownOn() { return shownOn; }
	public void setShownOn(LocalDate shownOn) { this.shownOn = shownOn; }
}
