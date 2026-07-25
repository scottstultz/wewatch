package com.wewatch.api.dto;

import java.time.Instant;
import java.util.List;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.WatchStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record WatchlistEntryResponse(
	Long id,
	Long watchlistId,
	Long addedByUserId,
	Long titleId,
	String externalId,
	String externalSource,
	String name,
	@Schema(description = "Whether the title is a movie or a TV show", allowableValues = {"MOVIE", "TV"})
	TitleType type,
	String posterUrl,
	@Schema(description = "The entry's watch status", allowableValues = {"WANT_TO_WATCH", "WATCHING", "WATCHED"})
	WatchStatus status,
	Instant addedAt,
	Instant updatedAt,
	Instant startedAt,
	Instant completedAt,
	@Schema(description = "Episode-progress summary; present only for TV entries, null for movies")
	EpisodeProgressSummary episodeProgress,
	// The caller's own thumbs rating (#273) — personal, not the entry's;
	// two members of a shared list see different values here
	@Schema(description = "The caller's own thumbs rating for this title (#273) — personal to the caller, "
		+ "not shared with other watchlist members. Null if the caller hasn't rated the title.",
		allowableValues = {"UP", "DOWN"})
	Rating myRating,
	// TMDB genre ids from the title cache (#381), so a client can filter or label without a
	// second call. Always present; empty when the title isn't cached yet, or when its cached
	// row belongs to the other medium (see TmdbCacheService.genreIdsByTitleId).
	@Schema(description = "TMDB genre ids for this title, from our own title cache (#381). Empty — "
		+ "never null — when the title has not been cached yet, so a caller filtering on genre can "
		+ "treat it as 'no known genres' rather than handling a missing field.")
	List<Integer> genreIds,
	// Which of the caller's configured streaming services carry this title (#392), from the
	// same title cache read as genreIds. Personal to the caller, like myRating — two members
	// of a shared list with different services see different badges here.
	@Schema(description = "Which of the caller's own configured streaming services carry this title "
		+ "(#392). Empty — never null — when the title isn't cached yet, its cached row belongs to "
		+ "the other medium, or the caller has no streaming services configured.")
	List<Integer> providerIds
) {
}
