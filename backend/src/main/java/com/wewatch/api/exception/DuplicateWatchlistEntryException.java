package com.wewatch.api.exception;

public class DuplicateWatchlistEntryException extends RuntimeException {

	public DuplicateWatchlistEntryException(Long watchlistId, Long titleId) {
		super("Watchlist entry already exists for watchlist " + watchlistId + " and title " + titleId);
	}
}
