package com.wewatch.api.exception;

public class DuplicateTitleException extends RuntimeException {

	public DuplicateTitleException(String externalSource, String externalId) {
		super("Title already exists for source " + externalSource + " and external id " + externalId);
	}
}
