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
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(
	name = "watchlist_entries",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_watchlist_entries_user_title", columnNames = {"user_id", "title_id"})
	}
)
public class WatchlistEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotNull
	@Column(name = "user_id", nullable = false)
	private Long userId;

	@NotNull
	@Column(name = "title_id", nullable = false)
	private Long titleId;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private WatchStatus status;

	@NotNull
	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	@NotNull
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	public WatchlistEntry() {
	}

	public WatchlistEntry(
		Long id,
		Long userId,
		Long titleId,
		WatchStatus status,
		Instant addedAt,
		Instant updatedAt,
		Instant startedAt,
		Instant completedAt
	) {
		this.id = id;
		this.userId = userId;
		this.titleId = titleId;
		this.status = status;
		this.addedAt = addedAt;
		this.updatedAt = updatedAt;
		this.startedAt = startedAt;
		this.completedAt = completedAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public Long getTitleId() {
		return titleId;
	}

	public void setTitleId(Long titleId) {
		this.titleId = titleId;
	}

	public WatchStatus getStatus() {
		return status;
	}

	public void setStatus(WatchStatus status) {
		this.status = status;
	}

	public Instant getAddedAt() {
		return addedAt;
	}

	public void setAddedAt(Instant addedAt) {
		this.addedAt = addedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}

	@AssertTrue(message = "completedAt must be set only for watched entries")
	boolean hasValidCompletedAt() {
		if (status == null) {
			return true;
		}

		if (status == WatchStatus.WATCHED) {
			return completedAt != null;
		}

		return completedAt == null;
	}

	@AssertTrue(message = "startedAt must be set only for active or completed entries")
	boolean hasValidStartedAt() {
		if (status == null) {
			return true;
		}

		if (status == WatchStatus.WANT_TO_WATCH) {
			return startedAt == null;
		}

		return startedAt != null;
	}
}
