package com.wewatch.api.tuning;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The offline suggestion-tuning harness entry point (#288). Tagged
 * {@code tuning} and excluded from the default {@code ./mvnw test} run;
 * invoke it explicitly:
 *
 * <pre>{@code   ./mvnw test -Ptuning}</pre>
 *
 * Each method computes shelves for every fixture watchlist against the
 * {@link SyntheticCatalog} — no live TMDB, fully reproducible for a fixed day
 * seed — and writes a readable report to {@code target/tuning/} as well as
 * stdout. See {@code src/test/resources/tuning/README.md} for how to add a
 * fixture watchlist, record TMDB responses, and interpret the reports.
 */
@Tag("tuning")
class SuggestionTuningHarness {

	private final SuggestionTuningRunner runner = new SuggestionTuningRunner();

	/** Baseline shelves for every fixture — the reference report. */
	@Test
	void baselineShelves() {
		String report = runner.renderParameterSet(TuningParameters.baseline());
		emit("baseline-shelves.txt", report);
	}

	/**
	 * The documented example run (#288 AC): halve KEYWORD_MATCH_WEIGHT and diff
	 * against baseline. Keyword-heavy profiles (rich-single-genre) lose the
	 * keyword tiebreak that floated shared-theme candidates up, so those titles
	 * slip in rank or out of the shelf while genre-only peers rise.
	 */
	@Test
	void halveKeywordWeightDiff() {
		TuningParameters baseline = TuningParameters.baseline();
		TuningParameters halfKeyword = TuningParameters.derive("keyword-weight-halved",
			p -> p.setKeywordMatchWeight(p.getKeywordMatchWeight() / 2));
		String report = runner.diffParameterSets(baseline, halfKeyword);
		emit("diff-keyword-weight-halved.txt", report);
	}

	/**
	 * A second worked example: double the recency half-life so historical taste
	 * decays half as fast. Most visible on stale-history, where the old crime
	 * catalogue claws back genre weight from the fresh animation titles.
	 */
	@Test
	void doubleHalfLifeDiff() {
		TuningParameters baseline = TuningParameters.baseline();
		TuningParameters slowDecay = TuningParameters.derive("half-life-doubled",
			p -> p.setHalfLifeDays(p.getHalfLifeDays() * 2));
		String report = runner.diffParameterSets(baseline, slowDecay);
		emit("diff-half-life-doubled.txt", report);
	}

	/** Rotation across a simulated week under baseline parameters. */
	@Test
	void weekSweep() {
		String report = runner.sweepDays(TuningParameters.baseline(), 7);
		emit("sweep-week.txt", report);
	}

	private void emit(String fileName, String report) {
		Path out = SuggestionTuningRunner.writeReport(fileName, report);
		System.out.println(report);
		System.out.println("→ written to " + out.toAbsolutePath());
	}
}
