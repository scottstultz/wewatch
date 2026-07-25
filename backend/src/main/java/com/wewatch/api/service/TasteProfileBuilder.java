package com.wewatch.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;

// Turns a watchlist into a TasteProfile: genre affinities, top keywords, and
// recurring people, each rating-weighted (#273) and recency-decayed (#274).
// Pure with respect to the pipeline — no TMDB calls and no draws from the daily
// rng — so a list's profile is identical across recomputes within a day (#231).
class TasteProfileBuilder {

	private static final int MAX_GENRES = 3;
	private static final int MAX_PEOPLE = 5;
	// One appearance is just a cast list; recurring across the user's titles is
	// the "you keep watching this person" signal the shelf label claims
	private static final int PERSON_MIN_TITLE_COUNT = 2;

	private final Clock clock;
	private final SuggestionTuningProperties tuning;

	TasteProfileBuilder(Clock clock, SuggestionTuningProperties tuning) {
		this.clock = clock;
		this.tuning = tuning;
	}

	TasteProfile build(
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId,
		Map<Long, Rating> ratingsByTitleId,
		LocalDate today
	) {
		// Thumbs ratings (#273) and the recency decay (#274) re-keyed by cache key (#394 —
		// a bare externalId is not an identity) for the cache-row-driven keyword/person
		// builders below, which look up by TmdbTitleCache::getTmdbId
		Map<String, Rating> ratingsByTmdbId = new HashMap<>();
		Map<String, Double> decayByTmdbId = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getExternalId() == null) continue;
			String cacheKey = TmdbCacheKey.of(t);
			Rating rating = ratingsByTitleId.get(e.getTitleId());
			if (rating != null) {
				ratingsByTmdbId.put(cacheKey, rating);
			}
			decayByTmdbId.put(cacheKey, recencyDecay(e, today));
		}

		Collection<TmdbTitleCache> caches = cacheByTmdbId.values();

		Map<TitleType, List<Integer>> topGenresByType = new HashMap<>();
		for (TitleType type : TitleType.values()) {
			topGenresByType.put(type,
				topGenresFor(type, entries, titlesById, cacheByTmdbId, ratingsByTitleId, today));
		}

		return TasteProfile.of(
			buildGenreProfile(entries, titlesById, cacheByTmdbId, ratingsByTitleId, today),
			buildKeywordAffinities(caches, ratingsByTmdbId, decayByTmdbId),
			buildPersonProfile(caches, ratingsByTmdbId, decayByTmdbId),
			topGenresByType,
			dominantType(entries, titlesById));
	}

	// Recency-decayed (#274): a show added two years ago no longer shapes the
	// profile as much as one added this week — current taste dominates, while
	// the decay floor keeps history counting for something.
	private Map<Integer, Double> buildGenreProfile(
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId,
		Map<Long, Rating> ratingsByTitleId,
		LocalDate today
	) {
		Map<Integer, Double> profile = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(TmdbCacheKey.of(t));
			if (cached == null || cached.getGenreIds() == null) continue;
			double weight = profileWeight(e.getStatus(), ratingsByTitleId.get(e.getTitleId()))
				* recencyDecay(e, today);
			for (int genreId : cached.getGenreIds()) {
				profile.merge(genreId, weight, Double::sum);
			}
		}
		return profile;
	}

	// Rating-weighted (#273): a rated-up title counts double toward its genres
	// making the discover filter cut, a rated-down title counts against them,
	// and a genre with non-positive weighted frequency can't qualify at all —
	// unrated entries keep the old 1-per-title counting exactly.
	// Recency-decayed (#274): an old entry's vote fades toward the floor, so
	// the filter cut tracks what the user watches now.
	private List<Integer> topGenresFor(
		TitleType type,
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId,
		Map<Long, Rating> ratingsByTitleId,
		LocalDate today
	) {
		Map<Integer, Double> genreFreq = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getType() != type || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(TmdbCacheKey.of(t));
			if (cached == null || cached.getGenreIds() == null) continue;
			double weight = signalWeight(ratingsByTitleId.get(e.getTitleId())) * recencyDecay(e, today);
			for (int genreId : cached.getGenreIds()) {
				genreFreq.merge(genreId, weight, Double::sum);
			}
		}
		return genreFreq.entrySet().stream()
			.filter(en -> en.getValue() > 0)
			.sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
				.thenComparing(Map.Entry::getKey))
			.limit(MAX_GENRES)
			.map(Map.Entry::getKey)
			.toList();
	}

	// Frequency counting stays on the flat keyword_ids column (every row has it);
	// names come from the keywords JSON where present. Ties break by id so the
	// profile is stable across recomputes — the daily rng never touches profile
	// construction (#231). Rating-weighted (#273): a rated-down title's keywords
	// count negatively, so a keyword carried only by disliked titles can't make
	// the profile, and one shared with liked titles ranks lower.
	// Recency-decayed (#274): a keyword riding only old entries fades out of
	// the top-N cut as fresher titles bring their own.
	private List<KeywordAffinity> buildKeywordAffinities(
		Collection<TmdbTitleCache> caches,
		Map<String, Rating> ratingsByTmdbId,
		Map<String, Double> decayByTmdbId
	) {
		Map<Integer, Double> freq = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			if (c.getKeywordIds() == null) continue;
			double weight = signalWeight(ratingsByTmdbId.get(c.getTmdbId()))
				* decayByTmdbId.getOrDefault(c.getTmdbId(), 1.0);
			for (int kw : c.getKeywordIds()) freq.merge(kw, weight, Double::sum);
			if (c.getKeywords() != null) {
				c.getKeywords().forEach(k -> names.putIfAbsent(k.id(), k.name()));
			}
		}
		return freq.entrySet().stream()
			.filter(e -> e.getValue() > 0)
			.sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
				.thenComparing(Map.Entry::getKey))
			.limit(TasteProfile.MAX_KEYWORDS)
			.map(e -> new KeywordAffinity(e.getKey(), names.get(e.getKey())))
			.toList();
	}

	// Frequency-weighted like the keyword profile, but floored at
	// PERSON_MIN_TITLE_COUNT: keywords are meaningful once, a person only through
	// recurrence. Ties break by id so the profile is stable across recomputes —
	// the daily rng never touches profile construction (#231).
	// Rating-weighted (#273): appearances in rated-up titles count double toward
	// the ranking and rated-down appearances count against it, but the recurrence
	// floor stays a plain appearance count over non-down-rated titles — one loved
	// title still isn't "you keep watching this person", and a person carried
	// only by disliked titles (score ≤ 0) drops out of the profile entirely.
	// Recency-decayed (#274) via the entry-derived per-title factor, like the
	// keyword profile; the recurrence floor stays an undecayed count for the
	// same reason it ignores rating weight — recurrence is about how often,
	// not how recently.
	private List<PersonAffinity> buildPersonProfile(
		Collection<TmdbTitleCache> caches,
		Map<String, Rating> ratingsByTmdbId,
		Map<String, Double> decayByTmdbId
	) {
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, Integer> castCounts = new HashMap<>();
		Map<Integer, Integer> directorCounts = new HashMap<>();
		Map<Integer, Integer> positiveCounts = new HashMap<>();
		Map<Integer, Double> scores = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			Rating rating = ratingsByTmdbId.get(c.getTmdbId());
			double weight = signalWeight(rating) * decayByTmdbId.getOrDefault(c.getTmdbId(), 1.0);
			if (c.getTopCast() != null) {
				for (CachedPerson p : c.getTopCast()) {
					castCounts.merge(p.id(), 1, Integer::sum);
					scores.merge(p.id(), weight, Double::sum);
					if (rating != Rating.DOWN) positiveCounts.merge(p.id(), 1, Integer::sum);
					names.putIfAbsent(p.id(), p.name());
				}
			}
			if (c.getDirectors() != null) {
				for (CachedPerson p : c.getDirectors()) {
					directorCounts.merge(p.id(), 1, Integer::sum);
					scores.merge(p.id(), weight, Double::sum);
					if (rating != Rating.DOWN) positiveCounts.merge(p.id(), 1, Integer::sum);
					names.putIfAbsent(p.id(), p.name());
				}
			}
		}
		return names.keySet().stream()
			.map(id -> {
				int cast = castCounts.getOrDefault(id, 0);
				int directed = directorCounts.getOrDefault(id, 0);
				return new PersonAffinity(id, names.get(id), scores.getOrDefault(id, 0.0),
					directed > 0 && directed >= cast);
			})
			.filter(p -> positiveCounts.getOrDefault(p.id(), 0) >= PERSON_MIN_TITLE_COUNT
				&& p.score() > 0)
			.sorted(Comparator.comparingDouble(PersonAffinity::score).reversed()
				.thenComparing(PersonAffinity::id))
			.limit(MAX_PEOPLE)
			.toList();
	}

	// Exploration shelves are built once per compute, for the medium the list
	// leans toward, to bound TMDB call count; ties go to TV
	private TitleType dominantType(List<WatchlistEntry> entries, Map<Long, Title> titlesById) {
		long movies = entries.stream()
			.map(e -> titlesById.get(e.getTitleId()))
			.filter(t -> t != null && t.getType() == TitleType.MOVIE)
			.count();
		long shows = entries.stream()
			.map(e -> titlesById.get(e.getTitleId()))
			.filter(t -> t != null && t.getType() == TitleType.TV)
			.count();
		return movies > shows ? TitleType.MOVIE : TitleType.TV;
	}

	// Taste-profile weights only. Seed eligibility is a separate filter
	// (WATCHING / WANT_TO_WATCH): finished titles shape the profile — finishing is
	// the strongest completed-interest signal — but don't get per-seed shelves.
	// An explicit thumbs rating (#273) overrides the status inference entirely:
	// up elevates above any status weight, down goes negative regardless of
	// status (finishing a show you hated is not an endorsement of its genres).
	private double profileWeight(WatchStatus status, Rating rating) {
		if (rating == Rating.UP) return tuning.getRatedUpProfileWeight();
		if (rating == Rating.DOWN) return tuning.getRatedDownProfileWeight();
		return switch (status) {
			case WATCHING, WATCHED -> tuning.getWatchedProfileWeight();
			case WANT_TO_WATCH -> tuning.getWantToWatchProfileWeight();
		};
	}

	// Recency decay for the taste profile (#274): taste drifts, so an entry's
	// vote halves every configured half-life from its last touch (updated_at
	// covers both adding and status changes; added_at is the legacy fallback).
	// Day-granular off the injected clock so profiles are stable within a day,
	// preserving same-day shelf reproducibility (#231) the way the recency-
	// penalty reads do. The floor keeps old entries counting — history still
	// matters, just less than last month's watching.
	private double recencyDecay(WatchlistEntry entry, LocalDate today) {
		Instant touched = entry.getUpdatedAt() != null ? entry.getUpdatedAt() : entry.getAddedAt();
		if (touched == null) return 1.0;
		long ageDays = Math.max(0,
			today.toEpochDay() - LocalDate.ofInstant(touched, clock.getZone()).toEpochDay());
		return Math.max(tuning.getDecayFloor(), Math.pow(0.5, ageDays / tuning.getHalfLifeDays()));
	}

	// Per-title multiplier for the frequency-counted profiles (keyword, person,
	// top-genre cut), where the unrated baseline is 1 per title regardless of
	// status — unrated behavior stays exactly as before (#273)
	private double signalWeight(Rating rating) {
		if (rating == Rating.UP) return tuning.getRatedUpSignalWeight();
		if (rating == Rating.DOWN) return tuning.getRatedDownSignalWeight();
		return tuning.getUnratedSignalWeight();
	}
}
