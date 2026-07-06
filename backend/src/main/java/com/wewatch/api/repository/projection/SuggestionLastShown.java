package com.wewatch.api.repository.projection;

import java.time.LocalDate;

/**
 * The most recent previous day a suggested title was shown to any of the
 * queried users — the input to the soft recency penalty (#264).
 */
public interface SuggestionLastShown {

	String getTmdbId();

	LocalDate getShownOn();
}
