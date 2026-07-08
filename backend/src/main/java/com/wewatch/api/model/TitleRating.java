package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "title_ratings",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_title_ratings_user_title", columnNames = {"user_id", "title_id"})
	}
)
public class TitleRating {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "title_id", nullable = false)
	private Long titleId;

	@Enumerated(EnumType.STRING)
	@Column(name = "rating", nullable = false, length = 8)
	private Rating rating;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public TitleRating() {
	}

	public Long getId() { return id; }

	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }

	public Long getTitleId() { return titleId; }
	public void setTitleId(Long titleId) { this.titleId = titleId; }

	public Rating getRating() { return rating; }
	public void setRating(Rating rating) { this.rating = rating; }

	public Instant getCreatedAt() { return createdAt; }
	public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
