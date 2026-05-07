package com.wewatch.api.exception;

public class DuplicateWatchlistEntryException extends RuntimeException {

	public DuplicateWatchlistEntryException(Long userId, Long titleId) {
		super("Watchlist entry already exists for user " + userId + " and title " + titleId);
	}
}
