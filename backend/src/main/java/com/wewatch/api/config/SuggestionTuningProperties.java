package com.wewatch.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The hand-tuned constants of the suggestion taste profile (#288), extracted
 * from {@code SuggestionService} so they are injectable for production tuning
 * and for the offline tuning harness ({@code SuggestionTuningHarness} in the
 * test tree), which replays fixture watchlists and diffs shelves across
 * parameter sets. Defaults are the values each constant carried when it was
 * introduced (#232, #248, #264, #267, #269–#274) — an unset property changes
 * nothing.
 *
 * Structural knobs (shelf sizes, page depths, TMDB call budgets) deliberately
 * stay constants in {@code SuggestionService}: they bound cost and layout, not
 * taste, and sweeping them is not what the harness is for.
 */
@ConfigurationProperties(prefix = "suggestions.tuning")
public class SuggestionTuningProperties {

	// ── Genre-profile weights (#232/#273) ─────────────────────
	// Status-inferred weights, overridden entirely by an explicit rating: a
	// rated-up title outweighs any status signal, a rated-down title steers
	// the profile away from its genres.
	private double watchedProfileWeight = 2.0;
	private double wantToWatchProfileWeight = 1.0;
	private double ratedUpProfileWeight = 4.0;
	private double ratedDownProfileWeight = -2.0;

	// ── Frequency-profile rating multipliers (#273) ───────────
	// For the keyword/person/top-genre profiles, whose unrated baseline is
	// 1 per title regardless of status.
	private double ratedUpSignalWeight = 2.0;
	private double ratedDownSignalWeight = -1.0;
	private double unratedSignalWeight = 1.0;

	// ── Candidate score boosts ────────────────────────────────
	// A shared keyword (#232) beats any single genre weight; a shared person
	// (#269) is the sharpest per-title signal; streamability (#270) sits
	// between them — orthogonal to taste, but a title the user can actually
	// press play on beats a marginally better-matched one they can't.
	private double keywordMatchWeight = 2.0;
	private double personMatchWeight = 3.0;
	private double streamableBoost = 2.5;

	// ── Day-seeded score jitter (#248/#267) ───────────────────
	// Amplitude proportional to the candidate's base score, with an absolute
	// floor that keeps zero/low-score candidates rotating without lifting
	// them over a real (≥1) genre match.
	private double scoreJitterFraction = 0.15;
	private double scoreJitterFloor = 0.25;

	// ── Taste-profile recency decay (#274) ────────────────────
	// An entry's profile contribution halves every half-life from its last
	// touch, floored so history never zeroes out (0.2 is reached ~209 days
	// out at the default half-life).
	private double halfLifeDays = 90.0;
	private double decayFloor = 0.2;

	// ── Soft recency penalty (#264) ───────────────────────────
	// Positional demotion in fillShelf: shown-yesterday sinks this many
	// positions (past a full shelf), decaying linearly over the impression
	// window.
	private double recencyDemotion = 16.0;

	public double getWatchedProfileWeight() {
		return watchedProfileWeight;
	}

	public void setWatchedProfileWeight(double watchedProfileWeight) {
		this.watchedProfileWeight = watchedProfileWeight;
	}

	public double getWantToWatchProfileWeight() {
		return wantToWatchProfileWeight;
	}

	public void setWantToWatchProfileWeight(double wantToWatchProfileWeight) {
		this.wantToWatchProfileWeight = wantToWatchProfileWeight;
	}

	public double getRatedUpProfileWeight() {
		return ratedUpProfileWeight;
	}

	public void setRatedUpProfileWeight(double ratedUpProfileWeight) {
		this.ratedUpProfileWeight = ratedUpProfileWeight;
	}

	public double getRatedDownProfileWeight() {
		return ratedDownProfileWeight;
	}

	public void setRatedDownProfileWeight(double ratedDownProfileWeight) {
		this.ratedDownProfileWeight = ratedDownProfileWeight;
	}

	public double getRatedUpSignalWeight() {
		return ratedUpSignalWeight;
	}

	public void setRatedUpSignalWeight(double ratedUpSignalWeight) {
		this.ratedUpSignalWeight = ratedUpSignalWeight;
	}

	public double getRatedDownSignalWeight() {
		return ratedDownSignalWeight;
	}

	public void setRatedDownSignalWeight(double ratedDownSignalWeight) {
		this.ratedDownSignalWeight = ratedDownSignalWeight;
	}

	public double getUnratedSignalWeight() {
		return unratedSignalWeight;
	}

	public void setUnratedSignalWeight(double unratedSignalWeight) {
		this.unratedSignalWeight = unratedSignalWeight;
	}

	public double getKeywordMatchWeight() {
		return keywordMatchWeight;
	}

	public void setKeywordMatchWeight(double keywordMatchWeight) {
		this.keywordMatchWeight = keywordMatchWeight;
	}

	public double getPersonMatchWeight() {
		return personMatchWeight;
	}

	public void setPersonMatchWeight(double personMatchWeight) {
		this.personMatchWeight = personMatchWeight;
	}

	public double getStreamableBoost() {
		return streamableBoost;
	}

	public void setStreamableBoost(double streamableBoost) {
		this.streamableBoost = streamableBoost;
	}

	public double getScoreJitterFraction() {
		return scoreJitterFraction;
	}

	public void setScoreJitterFraction(double scoreJitterFraction) {
		this.scoreJitterFraction = scoreJitterFraction;
	}

	public double getScoreJitterFloor() {
		return scoreJitterFloor;
	}

	public void setScoreJitterFloor(double scoreJitterFloor) {
		this.scoreJitterFloor = scoreJitterFloor;
	}

	public double getHalfLifeDays() {
		return halfLifeDays;
	}

	public void setHalfLifeDays(double halfLifeDays) {
		this.halfLifeDays = halfLifeDays;
	}

	public double getDecayFloor() {
		return decayFloor;
	}

	public void setDecayFloor(double decayFloor) {
		this.decayFloor = decayFloor;
	}

	public double getRecencyDemotion() {
		return recencyDemotion;
	}

	public void setRecencyDemotion(double recencyDemotion) {
		this.recencyDemotion = recencyDemotion;
	}
}
