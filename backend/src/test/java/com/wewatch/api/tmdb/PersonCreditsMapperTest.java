package com.wewatch.api.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;

class PersonCreditsMapperTest {

	private static TmdbPersonCredit movie(long id, String title, double popularity, String character) {
		return new TmdbPersonCredit(id, "movie", title, null, "An overview.", "1999-03-31", null,
			"/poster.jpg", List.of(28), popularity, 24000, character);
	}

	private static TmdbPersonCredit tv(long id, String name, double popularity, String character) {
		return new TmdbPersonCredit(id, "tv", null, name, "An overview.", null, "2016-09-15",
			"/poster.jpg", List.of(35), popularity, 40, character);
	}

	// A TV credit that declares how many episodes it covers — the dedup tie-break (#401)
	private static TmdbPersonCredit tvEpisodes(long id, String name, double popularity,
			String character, Integer episodeCount) {
		return new TmdbPersonCredit(id, "tv", null, name, "An overview.", null, "2016-09-15",
			"/poster.jpg", List.of(35), popularity, 40, character, episodeCount);
	}

	private static TmdbPersonCredits cast(TmdbPersonCredit... credits) {
		return new TmdbPersonCredits(List.of(credits), List.of());
	}

	@Test
	void mapsMovieAndTvCreditsFromTheirOwnMediaType() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, "Neo"),
			tv(1408, "Swedish Dicks", 8.1, "Tex")
		));

		assertThat(credits).hasSize(2);

		TitleSearchResponse matrix = credits.get(0);
		assertThat(matrix.externalId()).isEqualTo("603");
		assertThat(matrix.externalSource()).isEqualTo("TMDB");
		assertThat(matrix.type()).isEqualTo(TitleType.MOVIE);
		assertThat(matrix.name()).isEqualTo("The Matrix");
		assertThat(matrix.releaseDate()).isEqualTo(LocalDate.parse("1999-03-31"));
		assertThat(matrix.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
		assertThat(matrix.genreIds()).containsExactly(28);

		TitleSearchResponse show = credits.get(1);
		assertThat(show.type()).isEqualTo(TitleType.TV);
		assertThat(show.name()).isEqualTo("Swedish Dicks");
		assertThat(show.releaseDate()).isEqualTo(LocalDate.parse("2016-09-15"));
	}

	@Test
	void sortsByPopularityDescendingWithNullsLast() {
		TmdbPersonCredit unranked = new TmdbPersonCredit(3, "movie", "Unranked", null, null,
			"2000-01-01", null, "/poster.jpg", List.of(), null, 10, "Extra");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(1, "Middling", 50.0, "Someone"),
			unranked,
			movie(2, "Blockbuster", 90.0, "Hero")
		));

		assertThat(credits).extracting(TitleSearchResponse::name)
			.containsExactly("Blockbuster", "Middling", "Unranked");
	}

	@Test
	void dropsMediaTypesOtherThanMovieAndTv() {
		TmdbPersonCredit episode = new TmdbPersonCredit(9, "tv_episode", null, "An Episode", null,
			null, "2020-01-01", "/poster.jpg", List.of(), 5.0, 10, "Guest");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, "Neo"),
			episode
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("The Matrix");
	}

	@Test
	void dropsCreditsWithoutAPoster() {
		TmdbPersonCredit posterless = new TmdbPersonCredit(9, "movie", "Obscure", null, null,
			"2020-01-01", null, null, List.of(), 5.0, 10, "Someone");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, "Neo"),
			posterless
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("The Matrix");
	}

	@Test
	void dropsSelfAppearancesButKeepsCharactersMerelyStartingWithSelf() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			tv(100, "Late Night", 80.0, "Self"),
			tv(101, "Awards Show", 70.0, "Self - Guest"),
			movie(102, "Selfridge Story", 60.0, "Selfridge")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("Selfridge Story");
	}

	@Test
	void dropsSelfAppearancesUnderAnyPronounAndCasing() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(100, "A Cameo", 80.0, "Himself"),
			movie(101, "Another Cameo", 79.0, "HimSelf"),
			tv(102, "A Show", 78.0, "Herself - Narrator"),
			tv(103, "Archive Footage", 77.0, "Self (archive footage)"),
			movie(104, "Real Role", 60.0, "Neo")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("Real Role");
	}

	// TMDB leaves the character blank on plenty of talk-show credits, so the genre
	// tag is what actually removes them
	@Test
	void dropsTalkNewsAndRealityCreditsEvenWithABlankCharacter() {
		TmdbPersonCredit talk = new TmdbPersonCredit(1, "tv", null, "The Tonight Show", null, null,
			"1993-09-13", "/poster.jpg", List.of(10767, 35), 90.0, 100, "");
		TmdbPersonCredit news = new TmdbPersonCredit(2, "tv", null, "Nightly News", null, null,
			"1990-01-01", "/poster.jpg", List.of(10763), 80.0, 100, null);
		TmdbPersonCredit reality = new TmdbPersonCredit(3, "tv", null, "Bambi", null, null,
			"2000-01-01", "/poster.jpg", List.of(10764), 70.0, 100, "Self");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			talk, news, reality, movie(4, "The Matrix", 60.0, "Neo")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("The Matrix");
	}

	// A blank character on a movie usually means a real film TMDB hasn't detailed yet
	@Test
	void keepsCreditsWithNoCharacterAtAll() {
		TmdbPersonCredit blank = new TmdbPersonCredit(9, "movie", "Undetailed Film", null, null,
			"2026-01-01", null, "/poster.jpg", List.of(18), 50.0, 0, "");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, null),
			blank
		));

		assertThat(credits).extracting(TitleSearchResponse::name)
			.containsExactly("The Matrix", "Undetailed Film");
	}

	// Playing a fictionalized version of yourself is a real role, and TMDB credits it
	// under the person's own name rather than "Self"
	@Test
	void keepsRolesCreditedUnderThePersonsOwnName() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(505, "Always Be My Maybe", 50.0, "Keanu Reeves")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("Always Be My Maybe");
	}

	// (uncredited) marks cameo/background appearances; (voice) alone is a real role
	@Test
	void dropsUncreditedCameosButKeepsLegitimateVoiceRoles() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			tv(95396, "Severance", 90.0, "Animated Lumon Administrative Building (voice) (uncredited)"),
			movie(301528, "Toy Story 4", 80.0, "Duke Caboom (voice)")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("Toy Story 4");
	}

	@Test
	void dedupesByMediaTypeAndIdKeepingTheMostPopular() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix (dual role)", 10.0, "Second Role"),
			movie(603, "The Matrix", 92.5, "Neo")
		));

		assertThat(credits).extracting(TitleSearchResponse::name).containsExactly("The Matrix");
	}

	@Test
	void carriesTheCreditedCharacterOntoEachCredit() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, "Thomas A. Anderson"),
			tv(1408, "Swedish Dicks", 8.1, "Tex")
		));

		assertThat(credits).extracting(TitleSearchResponse::character)
			.containsExactly("Thomas A. Anderson", "Tex");
	}

	// A blank character is a normal credit, not an error — the tile just shows no role
	@Test
	void blankAndMissingCharactersBecomeNullRatherThanEmptyText() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(1, "Undetailed Film", 90.0, ""),
			movie(2, "Also Undetailed", 80.0, "   "),
			movie(3, "No Character At All", 70.0, null),
			movie(4, "Padded", 60.0, "  Neo  ")
		));

		assertThat(credits).extracting(TitleSearchResponse::character)
			.containsExactly(null, null, null, "Neo");
	}

	// Every credit on one show ties on popularity (it is a per-title score), so
	// without this the printed role is whichever one TMDB happened to list first —
	// live, Cranston's Family Guy tile read "Dr. Jewish (voice)" (1 episode) instead
	// of the recurring "Bert (voice)" (4 episodes)
	@Test
	void dedupePrefersTheCreditCoveringTheMostEpisodes() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			tvEpisodes(1434, "Family Guy", 50.0, "Dr. Jewish (voice)", 1),
			tvEpisodes(1434, "Family Guy", 50.0, "Bert (voice)", 4),
			tvEpisodes(1434, "Family Guy", 50.0, "Man (voice)", 2)
		));

		assertThat(credits).hasSize(1);
		assertThat(credits.get(0).character()).isEqualTo("Bert (voice)");
	}

	// Movies carry no episode count, so the popularity order still decides — and a
	// TV credit that declares episodes must beat one that omits them entirely
	@Test
	void dedupeHandlesCreditsThatDeclareNoEpisodeCount() {
		List<TitleSearchResponse> dualRole = PersonCreditsMapper.toCredits(cast(
			movie(603, "The Matrix", 92.5, "Neo"),
			movie(603, "The Matrix", 92.5, "Second Role")
		));
		assertThat(dualRole.get(0).character()).isEqualTo("Neo");

		List<TitleSearchResponse> partialCounts = PersonCreditsMapper.toCredits(cast(
			tvEpisodes(1434, "Family Guy", 50.0, "Uncounted (voice)", null),
			tvEpisodes(1434, "Family Guy", 50.0, "Bert (voice)", 4)
		));
		assertThat(partialCounts.get(0).character()).isEqualTo("Bert (voice)");
	}

	// The tie-break picks the role, not the position: the winning credit must not
	// drag the title up or down the popularity-ordered filmography
	@Test
	void theEpisodeTieBreakDoesNotReorderTheFilmography() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(1, "Blockbuster", 90.0, "Hero"),
			tvEpisodes(1434, "Family Guy", 50.0, "Dr. Jewish (voice)", 1),
			tvEpisodes(1434, "Family Guy", 50.0, "Bert (voice)", 4),
			movie(2, "Obscure", 10.0, "Extra")
		));

		assertThat(credits).extracting(TitleSearchResponse::name)
			.containsExactly("Blockbuster", "Family Guy", "Obscure");
		assertThat(credits.get(1).character()).isEqualTo("Bert (voice)");
	}

	@Test
	void keepsAMovieAndATvShowSharingAnId() {
		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(
			movie(42, "A Movie", 50.0, "Someone"),
			tv(42, "A Show", 40.0, "Someone")
		));

		assertThat(credits).extracting(TitleSearchResponse::type)
			.containsExactly(TitleType.MOVIE, TitleType.TV);
	}

	@Test
	void capsAtOneHundredCredits() {
		TmdbPersonCredit[] many = IntStream.range(0, 150)
			.mapToObj(i -> movie(i, "Movie " + i, 150.0 - i, "Someone"))
			.toArray(TmdbPersonCredit[]::new);

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(many));

		assertThat(credits).hasSize(100);
		assertThat(credits.get(0).name()).isEqualTo("Movie 0");
		assertThat(credits.get(99).name()).isEqualTo("Movie 99");
	}

	@Test
	void toleratesNullCreditsAndNullCastList() {
		assertThat(PersonCreditsMapper.toCredits(null)).isEmpty();
		assertThat(PersonCreditsMapper.toCredits(new TmdbPersonCredits(null, null))).isEmpty();
		assertThat(PersonCreditsMapper.toCredits(new TmdbPersonCredits(List.of(), List.of()))).isEmpty();
	}

	@Test
	void malformedDatesDegradeToNullRatherThanFailing() {
		TmdbPersonCredit badDate = new TmdbPersonCredit(9, "movie", "Bad Date", null, null,
			"not-a-date", null, "/poster.jpg", List.of(), 5.0, 10, "Someone");

		List<TitleSearchResponse> credits = PersonCreditsMapper.toCredits(cast(badDate));

		assertThat(credits).hasSize(1);
		assertThat(credits.get(0).releaseDate()).isNull();
	}

}
