package com.wewatch.api.tmdb;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Lenient parsing for TMDB date strings. TMDB catalog data occasionally
 * contains malformed dates; a bad date on one item must degrade to null
 * rather than fail the whole response.
 */
public final class TmdbDates {

	private TmdbDates() {
	}

	public static LocalDate parse(String date) {
		if (date == null || date.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
