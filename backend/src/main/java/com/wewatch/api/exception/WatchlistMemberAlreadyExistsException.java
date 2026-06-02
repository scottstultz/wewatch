package com.wewatch.api.exception;

public class WatchlistMemberAlreadyExistsException extends RuntimeException {

	public WatchlistMemberAlreadyExistsException(Long watchlistId, Long userId) {
		super("User " + userId + " is already a member of watchlist " + watchlistId);
	}
}
