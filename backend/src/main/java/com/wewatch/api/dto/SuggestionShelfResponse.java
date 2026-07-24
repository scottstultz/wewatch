package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record SuggestionShelfResponse(
	@Schema(description = "Human-readable label for the shelf, e.g. \"Because you watched X\" or "
		+ "\"Directed by X\" — the exact wording depends on the shelf kind")
	String reason,
	List<TitleSearchResponse> titles,
	@Schema(description = "The signal the shelf was built from")
	ShelfKind kind,
	// true when the shelf's TMDB feed was restricted to the members' streaming
	// services (#270) — every title on it is streamable even where per-title
	// badge data is missing from the cache
	@Schema(description = "True when the shelf's candidate pool was restricted to titles "
		+ "streamable on the watchlist members' configured streaming services")
	boolean providerFiltered
) {
	public SuggestionShelfResponse(String reason, List<TitleSearchResponse> titles, ShelfKind kind) {
		this(reason, titles, kind, false);
	}

	@Schema(description = "Signal a suggestion shelf was built from: PER_SEED (similar to a title on "
		+ "the watchlist), FINISHED_SEED (similar to a title the members finished), MORE_PICKS "
		+ "(aggregate catch-all pooling leftovers from under-filled per-seed/finished-seed shelves), "
		+ "GENRE_PROFILE (discover feed filtered to the members' top genres), NEW_RELEASES "
		+ "(recent discover releases), HIDDEN_GEMS (reserved — no builder currently emits it), "
		+ "TRENDING (TMDB trending/week), PERSON (cast/director affinity), KEYWORD "
		+ "(a keyword from the taste profile), FRANCHISE (next unreleased/unwatched entry in a "
		+ "collection the members finished), BOTH_WATCH (streamable on a service every member of a "
		+ "shared list has)")
	public enum ShelfKind { PER_SEED, FINISHED_SEED, MORE_PICKS, GENRE_PROFILE, NEW_RELEASES, HIDDEN_GEMS, TRENDING, PERSON, KEYWORD, FRANCHISE, BOTH_WATCH }
}
