package com.wewatch.api.tuning;

import java.util.function.Consumer;

import com.wewatch.api.config.SuggestionTuningProperties;

/**
 * A named {@link SuggestionTuningProperties} set for the harness (#288).
 * {@link #baseline()} is the shipped configuration (all code defaults);
 * {@link #derive} produces a variant by mutating a fresh copy of the baseline,
 * so a diff always compares against the same reference point. Every field the
 * issue calls out — profile weights, boosts, jitter, half-life/decay floor,
 * recency demotion — is reachable through the properties object.
 */
record TuningParameters(String name, SuggestionTuningProperties properties) {

	static TuningParameters baseline() {
		return new TuningParameters("baseline", new SuggestionTuningProperties());
	}

	/** A baseline copy with the given mutation applied, under a new name. */
	static TuningParameters derive(String name, Consumer<SuggestionTuningProperties> mutation) {
		SuggestionTuningProperties p = copyOf(new SuggestionTuningProperties());
		mutation.accept(p);
		return new TuningParameters(name, p);
	}

	private static SuggestionTuningProperties copyOf(SuggestionTuningProperties src) {
		SuggestionTuningProperties p = new SuggestionTuningProperties();
		p.setWatchedProfileWeight(src.getWatchedProfileWeight());
		p.setWantToWatchProfileWeight(src.getWantToWatchProfileWeight());
		p.setRatedUpProfileWeight(src.getRatedUpProfileWeight());
		p.setRatedDownProfileWeight(src.getRatedDownProfileWeight());
		p.setRatedUpSignalWeight(src.getRatedUpSignalWeight());
		p.setRatedDownSignalWeight(src.getRatedDownSignalWeight());
		p.setUnratedSignalWeight(src.getUnratedSignalWeight());
		p.setKeywordMatchWeight(src.getKeywordMatchWeight());
		p.setPersonMatchWeight(src.getPersonMatchWeight());
		p.setStreamableBoost(src.getStreamableBoost());
		p.setScoreJitterFraction(src.getScoreJitterFraction());
		p.setScoreJitterFloor(src.getScoreJitterFloor());
		p.setHalfLifeDays(src.getHalfLifeDays());
		p.setDecayFloor(src.getDecayFloor());
		p.setRecencyDemotion(src.getRecencyDemotion());
		return p;
	}
}
