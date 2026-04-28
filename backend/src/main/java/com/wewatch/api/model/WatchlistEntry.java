package com.wewatch.api.model;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public class WatchlistEntry {

	private Long id;

	@NotNull
	private Long userId;

	@NotNull
	private Long titleId;

	@NotNull
	private WatchStatus status;

	@NotNull
	private Instant addedAt;

	@NotNull
	private Instant updatedAt;

	private Instant startedAt;

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
