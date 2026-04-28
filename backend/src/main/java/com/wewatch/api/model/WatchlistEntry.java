package com.wewatch.api.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class WatchlistEntry {

	private Long id;

	@NotBlank
	@Size(max = 255)
	private String titleName;

	@NotNull
	private WatchStatus status;

	@Min(1)
	@Max(5)
	private Integer rating;

	@Size(max = 2000)
	private String notes;

	@NotNull
	private Instant dateAdded;

	private LocalDate dateWatched;

	public WatchlistEntry() {
	}

	public WatchlistEntry(
		Long id,
		String titleName,
		WatchStatus status,
		Integer rating,
		String notes,
		Instant dateAdded,
		LocalDate dateWatched
	) {
		this.id = id;
		this.titleName = titleName;
		this.status = status;
		this.rating = rating;
		this.notes = notes;
		this.dateAdded = dateAdded;
		this.dateWatched = dateWatched;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTitleName() {
		return titleName;
	}

	public void setTitleName(String titleName) {
		this.titleName = titleName;
	}

	public WatchStatus getStatus() {
		return status;
	}

	public void setStatus(WatchStatus status) {
		this.status = status;
	}

	public Integer getRating() {
		return rating;
	}

	public void setRating(Integer rating) {
		this.rating = rating;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Instant getDateAdded() {
		return dateAdded;
	}

	public void setDateAdded(Instant dateAdded) {
		this.dateAdded = dateAdded;
	}

	public LocalDate getDateWatched() {
		return dateWatched;
	}

	public void setDateWatched(LocalDate dateWatched) {
		this.dateWatched = dateWatched;
	}

	@AssertTrue(message = "dateWatched must be set only for watched entries")
	boolean hasValidWatchedDate() {
		if (status == null) {
			return true;
		}

		if (status == WatchStatus.WATCHED) {
			return dateWatched != null;
		}

		return dateWatched == null;
	}
}
