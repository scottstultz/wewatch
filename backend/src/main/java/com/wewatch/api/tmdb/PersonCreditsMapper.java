package com.wewatch.api.tmdb;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;

/**
 * Turns a person's TMDB combined_credits into the filmography the person page
 * shows (#304): acting credits only, junk removed, most popular first.
 *
 * <p>There is deliberately no vote_count floor. It reads sensible for movies but
 * silently deletes real TV credits, whose vote counts run an order of magnitude
 * lower. Requiring a poster and dropping "Self" credits is what actually removes
 * the junk.
 */
public final class PersonCreditsMapper {

	private static final int CREDIT_LIMIT = 100;

	// TMDB TV genres: Talk, News, Reality. These ids exist only in TMDB's TV genre
	// list, so matching them unconditionally cannot drop a movie.
	private static final Set<Integer> NON_FICTION_TV_GENRES = Set.of(10767, 10763, 10764);

	// TMDB credits an appearance-as-yourself under any of these, sometimes with a
	// suffix ("Self - Guest", "Self (archive footage)", "Himself - Narrator")
	private static final List<String> SELF_ALIASES = List.of("self", "himself", "herself", "themself", "themselves");

	private PersonCreditsMapper() {
	}

	public static List<TitleSearchResponse> toCredits(TmdbPersonCredits credits) {
		if (credits == null || credits.cast() == null) {
			return List.of();
		}

		List<TmdbPersonCredit> ranked = credits.cast().stream()
			.filter(credit -> isSupportedMediaType(credit.mediaType()))
			// No poster means no tile to render
			.filter(credit -> credit.posterPath() != null)
			.filter(credit -> !isTalkShow(credit.genreIds()))
			.filter(credit -> !isSelfAppearance(credit.character()))
			.filter(credit -> !isUncredited(credit.character()))
			.sorted(Comparator.comparing(TmdbPersonCredit::popularity,
				Comparator.nullsLast(Comparator.reverseOrder())))
			.toList();

		// A person can hold two credits on one title — dual roles, or a recurring
		// TV guest arc. Popularity is a per-title score, so every credit on one
		// title ties on it and the sort above cannot pick between them; keeping
		// the first was arbitrary, which stopped being invisible once the role is
		// printed on the tile (#401). Prefer the credit covering the most
		// episodes — the recurring role rather than a one-episode walk-on. Ties
		// (and movies, which carry no episode count) keep the first, so the
		// popularity ordering of the map itself is untouched: merge replaces a
		// value in place without moving its insertion position.
		Map<String, TmdbPersonCredit> deduped = new LinkedHashMap<>();
		for (TmdbPersonCredit credit : ranked) {
			deduped.merge(credit.mediaType() + ":" + credit.id(), credit,
				(kept, candidate) -> episodeCount(candidate) > episodeCount(kept) ? candidate : kept);
		}

		return deduped.values().stream()
			.limit(CREDIT_LIMIT)
			.map(PersonCreditsMapper::toResponse)
			.toList();
	}

	// TMDB emits media types beyond movie and tv on combined_credits
	private static boolean isSupportedMediaType(String mediaType) {
		return "movie".equals(mediaType) || "tv".equals(mediaType);
	}

	// Late-night and daytime appearances are what "Self"-filtering is meant to remove,
	// but character text alone does not find them: TMDB leaves the character blank on
	// plenty of them (LIVE with Kelly and Mark, The Tonight Show with Jay Leno), and
	// those outrank real credits on popularity. The genre tag is the reliable signal.
	// Blank characters are otherwise kept — on a movie they usually mean a real film
	// whose cast TMDB hasn't detailed yet.
	private static boolean isTalkShow(List<Integer> genreIds) {
		return genreIds != null && genreIds.stream().anyMatch(NON_FICTION_TV_GENRES::contains);
	}

	// Catches the self-appearances embedded in otherwise scripted titles, which carry
	// no talk-show genre. Playing a fictionalized version of yourself is a real role,
	// though — TMDB credits those under the person's own name (Keanu Reeves in
	// "Always Be My Maybe"), not "Self", so they survive this filter.
	private static boolean isSelfAppearance(String character) {
		if (character == null) {
			return false;
		}
		String normalized = character.trim().toLowerCase(Locale.ROOT);
		return SELF_ALIASES.stream()
			.anyMatch(alias -> normalized.equals(alias) || normalized.startsWith(alias + " "));
	}

	// TMDB tags cameo and background appearances "(uncredited)" in the character text
	// (Keanu's voice cameo in "Severance"). Voice work itself is not the signal — Duke
	// Caboom (Toy Story 4) and Shadow (Sonic 3) are "(voice)" starring roles that belong
	// on the page — so this keys on "(uncredited)" only.
	private static boolean isUncredited(String character) {
		return character != null
			&& character.toLowerCase(Locale.ROOT).contains("(uncredited)");
	}

	// Absent on movie credits, and TMDB has been seen to omit it on TV ones too.
	// Treated as zero so a credit that does declare episodes always wins the tie.
	private static int episodeCount(TmdbPersonCredit credit) {
		return credit.episodeCount() != null ? credit.episodeCount() : 0;
	}

	// TMDB leaves the character blank on a small share of the credits that survive
	// the filters above (~3% live), and those are kept deliberately — on a movie a
	// blank character usually means a real film TMDB hasn't detailed yet. Blank
	// collapses to null so the client has one absent case to render nothing for,
	// rather than an empty string that reads as a role.
	private static String characterOrNull(String character) {
		return character == null || character.isBlank() ? null : character.trim();
	}

	private static TitleSearchResponse toResponse(TmdbPersonCredit credit) {
		boolean isMovie = "movie".equals(credit.mediaType());
		TitleType type = isMovie ? TitleType.MOVIE : TitleType.TV;
		String name = isMovie ? credit.title() : credit.name();
		String dateStr = isMovie ? credit.releaseDate() : credit.firstAirDate();

		return new TitleSearchResponse(
			String.valueOf(credit.id()),
			"TMDB",
			type,
			name,
			credit.overview(),
			TmdbDates.parse(dateStr),
			TmdbClient.posterUrl(credit.posterPath()),
			credit.genreIds() != null ? credit.genreIds() : List.of(),
			null,
			null,
			characterOrNull(credit.character())
		);
	}

}
