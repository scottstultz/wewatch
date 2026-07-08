package com.wewatch.api.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wewatch.api.dto.SuggestionShelfResponse;

/**
 * A fast, always-on guard (#288) that keeps the offline tuning harness from
 * rotting: it runs in the normal {@code ./mvnw test} suite (unlike the
 * {@code tuning}-tagged {@link SuggestionTuningHarness}), asserting the
 * fixtures load, the pipeline produces shelves offline, a parameter change
 * moves shelf composition, and the day sweep rotates. It exercises the same
 * machinery the heavy harness reports on, so a broken fixture or wiring bug
 * fails CI rather than surfacing only when someone runs the harness by hand.
 */
class SuggestionTuningHarnessTest {

	private final SuggestionTuningRunner runner = new SuggestionTuningRunner();

	@Test
	void allRegimeFixturesLoad() {
		List<String> names = runner.fixtures().stream().map(FixtureWorld::name).toList();
		assertThat(names).contains(
			"sparse-profile", "rich-single-genre", "genre-mixed", "heavily-rated", "stale-history");
	}

	@Test
	void baselineProducesShelvesOffline() {
		FixtureWorld rich = fixture("rich-single-genre");
		List<SuggestionShelfResponse> shelves =
			rich.compute(TuningParameters.baseline().properties(), SyntheticCatalog.BASE_DAY, new ImpressionStore());
		// A rich, coherent profile fills several shelf kinds
		assertThat(shelves).hasSizeGreaterThanOrEqualTo(3);
		assertThat(shelves).allSatisfy(s -> assertThat(s.titles()).isNotEmpty());
	}

	@Test
	void reportsAreDeterministic() {
		String first = runner.renderParameterSet(TuningParameters.baseline());
		String second = new SuggestionTuningRunner().renderParameterSet(TuningParameters.baseline());
		assertThat(first).isEqualTo(second);
	}

	@Test
	void parameterChangeMovesShelfComposition() {
		// Flipping the rated-down weight from a penalty to a strong reward is a
		// structural change: disliked-genre titles the profile steered away from
		// now steer toward it, so heavily-rated's shelves must differ.
		FixtureWorld rated = fixture("heavily-rated");
		Set<String> baseline = titleIds(rated.compute(
			TuningParameters.baseline().properties(), SyntheticCatalog.BASE_DAY, new ImpressionStore()));
		Set<String> flipped = titleIds(rated.compute(
			TuningParameters.derive("down-rewarded", p -> p.setRatedDownProfileWeight(4.0)).properties(),
			SyntheticCatalog.BASE_DAY, new ImpressionStore()));
		assertThat(baseline).isNotEqualTo(flipped);
	}

	@Test
	void daySweepRotatesAtLeastOnce() {
		// Over a week, at least one fixture's title set must change day-to-day —
		// the daily RNG reseed plus the accruing recency penalty guarantee it.
		FixtureWorld rich = fixture("rich-single-genre");
		ImpressionStore store = new ImpressionStore();
		Set<String> dayOne = null;
		boolean rotated = false;
		for (int d = 0; d < 7; d++) {
			LocalDate day = SyntheticCatalog.BASE_DAY.plusDays(d);
			Set<String> ids = titleIds(rich.compute(TuningParameters.baseline().properties(), day, store));
			if (dayOne == null) {
				dayOne = ids;
			} else if (!ids.equals(dayOne)) {
				rotated = true;
			}
		}
		assertThat(rotated).as("shelves rotate across the week").isTrue();
	}

	private FixtureWorld fixture(String name) {
		return runner.fixtures().stream()
			.filter(f -> f.name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private static Set<String> titleIds(List<SuggestionShelfResponse> shelves) {
		Set<String> ids = new LinkedHashSet<>();
		shelves.forEach(s -> s.titles().forEach(t -> ids.add(t.externalId())));
		return ids;
	}
}
