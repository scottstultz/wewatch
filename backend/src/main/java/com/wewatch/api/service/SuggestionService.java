package com.wewatch.api.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.tmdb.TmdbClient;

@Service
public class SuggestionService {

	private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
	private static final int MAX_SEEDS = 3;
	private static final int GENRE_GROUP_THRESHOLD = 3;
	private static final int MAX_GENRES = 3;
	private static final int MAX_KEYWORDS = 5;
	private static final int MIN_SHELF_SIZE = 3;
	private static final int MAX_SHELF_SIZE = 12;
	private static final int MAX_PER_GENRE_CLUSTER = 4;
	private static final int SIMILAR_TOP_UP_THRESHOLD = 5;
	private static final int DISCOVER_VOTE_COUNT_GTE = 100;
	private static final int MAX_FETCH_PAGE = 3;
	private static final int MAX_FINISHED_SEEDS = 1;
	private static final int RECENT_FINISHED_POOL = 5;
	// Exploration shelves (#235) each cost TMDB calls, so only a rotating subset
	// of the kinds appears on a given day — rotation itself is a freshness lever
	private static final int MAX_EXPLORATION_SHELVES = 2;
	private static final int NEW_RELEASE_WINDOW_DAYS = 60;
	// Recent releases haven't had time to accumulate votes, so the floor is far
	// below the genre-profile discover floor of 100
	private static final int NEW_RELEASE_VOTE_COUNT_GTE = 20;
	// High enough to keep vote_average.desc from surfacing obscure noise, low
	// enough to reach below the popularity head
	private static final int HIDDEN_GEM_VOTE_COUNT_GTE = 200;
	private static final String SORT_POPULARITY = "popularity.desc";
	private static final String SORT_VOTE_AVERAGE = "vote_average.desc";
	// A shared keyword is a much stronger similarity signal than a shared genre,
	// which only ever contributes profileWeight (1–2) per genre
	private static final double KEYWORD_MATCH_WEIGHT = 2.0;
	// Day-seeded ± offset added to each candidate's score so ranking rotates daily
	// beyond exact ties (#248). Calibrated against the score scale (genre affinity
	// ~1–6, keyword boost 2.0/match): large enough to reorder near-scores, small
	// enough that a clearly stronger affinity still wins on average.
	private static final double SCORE_JITTER = 1.0;

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final WatchlistMemberRepository watchlistMemberRepository;
	private final TitleService titleService;
	private final TmdbClient tmdbClient;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;
	private final SuggestionImpressionService suggestionImpressionService;
	private final Clock clock;

	// In-process cache: assumes a single backend instance. If the app ever scales
	// horizontally, each node caches (and invalidates) independently, so recompute
	// on one node won't refresh shelves served by another — move to a shared store then.
	private final Cache<Long, List<SuggestionShelfResponse>> cache;

	public SuggestionService(
		WatchlistEntryRepository watchlistEntryRepository,
		WatchlistMemberRepository watchlistMemberRepository,
		TitleService titleService,
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository tmdbTitleCacheRepository,
		SuggestionImpressionService suggestionImpressionService,
		Clock clock,
		@Value("${suggestions.cache.ttl-minutes}") long cacheTtlMinutes,
		@Value("${suggestions.cache.max-size}") long cacheMaxSize
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.watchlistMemberRepository = watchlistMemberRepository;
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
		this.suggestionImpressionService = suggestionImpressionService;
		this.clock = clock;
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
			.maximumSize(cacheMaxSize)
			.build();
	}

	public List<SuggestionShelfResponse> topPicks(Long watchlistId) {
		return cache.get(watchlistId, this::compute);
	}

	@Async
	public void recompute(Long watchlistId) {
		cache.put(watchlistId, compute(watchlistId));
	}

	private List<SuggestionShelfResponse> compute(Long watchlistId) {
		List<WatchlistEntry> allEntries = watchlistEntryRepository
			.findByWatchlistId(watchlistId, null, Pageable.unpaged())
			.getContent();

		if (allEntries.isEmpty()) return List.of();

		List<Long> titleIds = allEntries.stream().map(WatchlistEntry::getTitleId).toList();
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);

		Set<String> ownedExternalIds = allEntries.stream()
			.map(WatchlistEntry::getExternalId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		// Load cache entries once for genre + keyword profile building
		Map<String, TmdbTitleCache> cacheByTmdbId = tmdbTitleCacheRepository.findAllById(ownedExternalIds)
			.stream().collect(Collectors.toMap(TmdbTitleCache::getTmdbId, c -> c));

		Map<Integer, Double> genreProfile = buildGenreProfile(allEntries, titlesById, cacheByTmdbId);
		Set<Integer> keywordProfile = buildKeywordProfile(cacheByTmdbId.values());

		// Cross-shelf dedup: start from all owned externalIds
		Set<String> seen = new HashSet<>(ownedExternalIds);

		// Suppression follows the user, not the list (#247): a shelf answers to every
		// member's suppression state, so a title one member saw elsewhere stays hidden
		// here too. For a personal list this is just the single owner.
		List<Long> memberUserIds = watchlistMemberRepository.findUserIdsByWatchlistId(watchlistId);

		// Titles shown on previous days within the suppression window (#233):
		// excluded from shelves so rotation digs deeper into the candidate pool
		Set<String> suppressed = suggestionImpressionService.recentlyShownIds(memberUserIds);

		// Ids pulled back into a shelf via the top-up path (#246): tracked so we can
		// skip re-recording them below, preserving their original suppression clock
		Set<String> toppedUpIds = new HashSet<>();

		List<SuggestionShelfResponse> shelves = new ArrayList<>();

		// Seeded on (watchlist, calendar day): shelves are stable across recomputes
		// within a day and rotate at midnight for free (#231)
		Random rng = new Random(Objects.hash(watchlistId, LocalDate.now(clock).toEpochDay()));

		// ── Per-seed shelves ───────────────────────────────────────
		// Sort by id before shuffling: repository ordering isn't guaranteed, and the
		// shuffle must see the same input order to be reproducible within a day
		List<WatchlistEntry> eligibleSeeds = allEntries.stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHING || e.getStatus() == WatchStatus.WANT_TO_WATCH)
			.filter(e -> titlesById.containsKey(e.getTitleId()))
			.sorted(Comparator.comparing(WatchlistEntry::getId))
			.collect(Collectors.toCollection(ArrayList::new));
		Collections.shuffle(eligibleSeeds, rng);
		List<WatchlistEntry> seeds = eligibleSeeds.subList(0, Math.min(MAX_SEEDS, eligibleSeeds.size()));

		for (WatchlistEntry seed : seeds) {
			Title title = titlesById.get(seed.getTitleId());
			if (title == null || title.getExternalId() == null) continue;

			List<TitleSearchResponse> candidates = fetchScoredCandidates(title.getType(), title.getExternalId(), genreProfile, keywordProfile, rng);
			List<TitleSearchResponse> shelf = fillShelf(candidates, seen, suppressed, toppedUpIds);

			if (shelf.size() >= MIN_SHELF_SIZE) {
				String label = title.getName() != null
					? "Because you added " + title.getName()
					: "Because of your list";
				shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.PER_SEED));
			}
		}

		// ── Finished-show seeds (#235) ────────────────────────────
		// WATCHED entries never get "Because you added X" shelves (#232); instead
		// the most recent finishes compete for a "Because you finished X" shelf
		List<WatchlistEntry> finishedPool = allEntries.stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHED)
			.filter(e -> titlesById.containsKey(e.getTitleId()))
			.sorted(Comparator.comparing(WatchlistEntry::getUpdatedAt,
					Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(WatchlistEntry::getId))
			.limit(RECENT_FINISHED_POOL)
			.collect(Collectors.toCollection(ArrayList::new));
		Collections.shuffle(finishedPool, rng);

		for (WatchlistEntry seed : finishedPool.subList(0, Math.min(MAX_FINISHED_SEEDS, finishedPool.size()))) {
			Title title = titlesById.get(seed.getTitleId());
			if (title == null || title.getExternalId() == null) continue;

			List<TitleSearchResponse> candidates = fetchScoredCandidates(title.getType(), title.getExternalId(), genreProfile, keywordProfile, rng);
			List<TitleSearchResponse> shelf = fillShelf(candidates, seen, suppressed, toppedUpIds);

			if (shelf.size() >= MIN_SHELF_SIZE) {
				String label = title.getName() != null
					? "Because you finished " + title.getName()
					: "Because you finished a title";
				shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.FINISHED_SEED));
			}
		}

		// ── Genre-profile shelves (conditional, per media type) ───
		for (TitleType type : List.of(TitleType.TV, TitleType.MOVIE)) {
			List<WatchlistEntry> group = allEntries.stream()
				.filter(e -> {
					Title t = titlesById.get(e.getTitleId());
					return t != null && t.getType() == type;
				})
				.toList();

			if (group.size() < GENRE_GROUP_THRESHOLD) continue;

			List<Integer> topGenres = topGenresFor(type, allEntries, titlesById, cacheByTmdbId);
			if (topGenres.isEmpty()) continue;

			// Type-scoped keywords to avoid cross-namespace contamination
			List<Integer> typeKeywords = group.stream()
				.map(e -> titlesById.get(e.getTitleId()))
				.filter(Objects::nonNull)
				.map(t -> cacheByTmdbId.get(t.getExternalId()))
				.filter(Objects::nonNull)
				.flatMap(c -> c.getKeywordIds() != null ? c.getKeywordIds().stream() : java.util.stream.Stream.empty())
				.collect(Collectors.groupingBy(k -> k, Collectors.summingInt(k -> 1)))
				.entrySet().stream()
				.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
				.limit(MAX_KEYWORDS)
				.map(Map.Entry::getKey)
				.toList();

			String label = type == TitleType.TV ? "More like your shows" : "More like your movies";
			int discoverPage = 1 + rng.nextInt(MAX_FETCH_PAGE);
			try {
				List<TitleSearchResponse> discovered = fetchPageWithFallback(
					p -> tmdbClient.discover(type, topGenres, typeKeywords, DISCOVER_VOTE_COUNT_GTE,
						SORT_POPULARITY, null, null, p), discoverPage);
				List<TitleSearchResponse> shelf = fillShelf(discovered, seen, suppressed, toppedUpIds);
				if (shelf.size() >= MIN_SHELF_SIZE) {
					shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.GENRE_PROFILE));
				}
			} catch (TmdbApiException e) {
				log.warn("Discover failed for {} genres {}: {}", type, topGenres, e.getMessage());
			}
		}

		// ── Exploration shelves (#235) ────────────────────────────
		// Try the kinds in a daily-rotated order and keep the first
		// MAX_EXPLORATION_SHELVES that fill — a kind that can't fill (no genre
		// data, empty TMDB response) yields its slot to the next one
		TitleType dominantType = dominantType(allEntries, titlesById);
		List<Integer> dominantGenres = topGenresFor(dominantType, allEntries, titlesById, cacheByTmdbId);

		List<SuggestionShelfResponse.ShelfKind> explorationOrder = new ArrayList<>(List.of(
			SuggestionShelfResponse.ShelfKind.NEW_RELEASES,
			SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS,
			SuggestionShelfResponse.ShelfKind.TRENDING));
		Collections.shuffle(explorationOrder, rng);

		int explorationCount = 0;
		for (SuggestionShelfResponse.ShelfKind kind : explorationOrder) {
			if (explorationCount >= MAX_EXPLORATION_SHELVES) break;
			SuggestionShelfResponse shelf = buildExplorationShelf(kind, dominantType, dominantGenres, genreProfile, seen, suppressed, toppedUpIds, rng);
			if (shelf != null) {
				shelves.add(shelf);
				explorationCount++;
			}
		}

		// Record what we're about to serve so tomorrow's compute suppresses it (#233).
		// Top-up titles (#246) are excluded: they were already suppressed and only
		// reappeared to keep a shelf at MIN_SHELF_SIZE, so re-recording them would reset
		// their suppression clock and trap them in the top-up path instead of aging out.
		Set<String> shownIds = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.filter(id -> !toppedUpIds.contains(id))
			.collect(Collectors.toSet());
		suggestionImpressionService.recordShown(memberUserIds, shownIds);

		return shelves;
	}

	// Exploration shelves (#235) trade similarity for discovery: recent releases,
	// well-rated titles outside the popularity head, and this week's trending —
	// still deduped, suppressed, and diversified like every other shelf. Returns
	// null when the kind can't produce a shelf so the caller can try the next kind.
	private SuggestionShelfResponse buildExplorationShelf(
		SuggestionShelfResponse.ShelfKind kind,
		TitleType type,
		List<Integer> topGenres,
		Map<Integer, Double> genreProfile,
		Set<String> seen,
		Set<String> suppressed,
		Set<String> toppedUpIds,
		Random rng
	) {
		int page = 1 + rng.nextInt(MAX_FETCH_PAGE);
		List<TitleSearchResponse> candidates;
		String label;
		try {
			switch (kind) {
				case NEW_RELEASES -> {
					if (topGenres.isEmpty()) return null;
					LocalDate today = LocalDate.now(clock);
					candidates = fetchPageWithFallback(p -> tmdbClient.discover(
						type, topGenres, List.of(), NEW_RELEASE_VOTE_COUNT_GTE,
						SORT_POPULARITY, today.minusDays(NEW_RELEASE_WINDOW_DAYS), today, p), page);
					label = "New in your genres";
				}
				case HIDDEN_GEMS -> {
					if (topGenres.isEmpty()) return null;
					candidates = fetchPageWithFallback(p -> tmdbClient.discover(
						type, topGenres, List.of(), HIDDEN_GEM_VOTE_COUNT_GTE,
						SORT_VOTE_AVERAGE, null, null, p), page);
					label = "Hidden gems";
				}
				case TRENDING -> {
					// Rank the raw popularity feed by taste-profile affinity plus
					// day-seeded jitter (#248) so the order rotates daily beyond ties;
					// with no genre profile the sort degrades to stable-per-day random
					List<TitleSearchResponse> trending = new ArrayList<>(
						fetchPageWithFallback(p -> tmdbClient.getTrending(type, p), page));
					Map<String, Double> jitter = jitterByCandidate(trending, rng);
					trending.sort(Comparator.comparingDouble((TitleSearchResponse r) ->
						genreScore(r, genreProfile) + jitter.getOrDefault(r.externalId(), 0.0)).reversed());
					candidates = trending;
					label = "Trending now";
				}
				default -> {
					return null;
				}
			}
		} catch (TmdbApiException e) {
			log.warn("Exploration shelf {} failed for {}: {}", kind, type, e.getMessage());
			return null;
		}

		List<TitleSearchResponse> shelf = fillShelf(candidates, seen, suppressed, toppedUpIds);
		return shelf.size() >= MIN_SHELF_SIZE ? new SuggestionShelfResponse(label, shelf, kind) : null;
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

	private List<Integer> topGenresFor(
		TitleType type,
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId
	) {
		Map<Integer, Integer> genreFreq = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getType() != type || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(t.getExternalId());
			if (cached == null || cached.getGenreIds() == null) continue;
			for (int genreId : cached.getGenreIds()) {
				genreFreq.merge(genreId, 1, Integer::sum);
			}
		}
		return genreFreq.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(MAX_GENRES)
			.map(Map.Entry::getKey)
			.toList();
	}

	// Fetch candidates from recommendations + similar top-up, then score by genre
	// affinity plus a boost per shared keyword
	private List<TitleSearchResponse> fetchScoredCandidates(TitleType type, String tmdbId, Map<Integer, Double> genreProfile, Set<Integer> keywordProfile, Random rng) {
		// One page draw per seed, shared by recommendations and similar, so RNG
		// consumption doesn't depend on how many results TMDB happens to return
		int page = 1 + rng.nextInt(MAX_FETCH_PAGE);
		List<TitleSearchResponse> results = new ArrayList<>();
		try {
			results.addAll(fetchPageWithFallback(p -> tmdbClient.getRecommendations(type, tmdbId, p), page));
		} catch (TmdbApiException e) {
			log.warn("Recommendations failed for {}: {}", tmdbId, e.getMessage());
		}
		if (results.size() < SIMILAR_TOP_UP_THRESHOLD) {
			try {
				results.addAll(fetchPageWithFallback(p -> tmdbClient.getSimilar(type, tmdbId, p), page));
			} catch (TmdbApiException e) {
				log.warn("Similar failed for {}: {}", tmdbId, e.getMessage());
			}
		}
		// Deduplicate within this fetch before scoring
		Set<String> seen = new HashSet<>();
		List<TitleSearchResponse> deduped = results.stream()
			.filter(r -> seen.add(r.externalId()))
			.collect(Collectors.toCollection(ArrayList::new));

		// Day-seeded score jitter (#248): a small ± offset per candidate rotates the
		// ranking daily beyond exact ties, so stable high-affinity candidates don't
		// float to the top of a shelf every single day. Applied even with no genre/
		// keyword signal, where the sort degrades to a stable-per-day random order.
		Map<String, Double> jitter = jitterByCandidate(deduped, rng);
		Map<String, Double> keywordBoosts = keywordBoosts(deduped, keywordProfile);

		deduped.sort(Comparator.comparingDouble((TitleSearchResponse r) ->
			genreScore(r, genreProfile)
			+ keywordBoosts.getOrDefault(r.externalId(), 0.0)
			+ jitter.getOrDefault(r.externalId(), 0.0)).reversed());
		return deduped;
	}

	// Day-seeded per-candidate score offset (#248). Draws from the shared day-seeded
	// rng in list order, so the assignment is reproducible within a day (identical
	// shelves across recomputes) and rotates at midnight. Keyed by externalId, one
	// draw per distinct id so duplicates don't desync the rng stream.
	private Map<String, Double> jitterByCandidate(List<TitleSearchResponse> candidates, Random rng) {
		Map<String, Double> jitter = new HashMap<>();
		for (TitleSearchResponse c : candidates) {
			jitter.computeIfAbsent(c.externalId(), id -> (rng.nextDouble() * 2 - 1) * SCORE_JITTER);
		}
		return jitter;
	}

	// TMDB recommendations/similar responses carry no keywords, so candidate keywords
	// come from tmdb_title_cache: candidates without a cache row get no boost —
	// a partial signal, but one batch DB read instead of a TMDB call per candidate
	private Map<String, Double> keywordBoosts(List<TitleSearchResponse> candidates, Set<Integer> keywordProfile) {
		if (keywordProfile.isEmpty() || candidates.isEmpty()) return Map.of();
		List<String> ids = candidates.stream().map(TitleSearchResponse::externalId).toList();
		Map<String, Double> boosts = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			if (cached.getKeywordIds() == null) continue;
			long matches = cached.getKeywordIds().stream().filter(keywordProfile::contains).count();
			if (matches > 0) boosts.put(cached.getTmdbId(), KEYWORD_MATCH_WEIGHT * matches);
		}
		return boosts;
	}

	// Deeper pages can be empty (few recommendations, thin discover results) — fall
	// back to page 1 rather than dropping the shelf for falling under MIN_SHELF_SIZE
	private List<TitleSearchResponse> fetchPageWithFallback(IntFunction<List<TitleSearchResponse>> fetch, int page) {
		List<TitleSearchResponse> results = fetch.apply(page);
		return results.isEmpty() && page > 1 ? fetch.apply(1) : results;
	}

	// Fill a shelf with diversification: cap same-primary-genre candidates to MAX_PER_GENRE_CLUSTER.
	// Recently shown candidates (#233) are held back; if that leaves the shelf under
	// MIN_SHELF_SIZE, the best of them top it back up rather than hiding the shelf.
	// Top-up ids are collected into toppedUpIds so the caller can skip re-recording
	// them as impressions (#246) — re-recording would reset their suppression clock and
	// keep them stuck in the top-up path forever instead of aging out.
	private List<TitleSearchResponse> fillShelf(List<TitleSearchResponse> candidates, Set<String> seen, Set<String> suppressed, Set<String> toppedUpIds) {
		Map<Integer, Integer> genreCount = new HashMap<>();
		List<TitleSearchResponse> shelf = new ArrayList<>();
		List<TitleSearchResponse> heldBack = new ArrayList<>();
		for (TitleSearchResponse r : candidates) {
			if (!seen.add(r.externalId())) continue;
			if (suppressed.contains(r.externalId())) {
				heldBack.add(r);
				continue;
			}
			int primaryGenre = primaryGenre(r);
			if (primaryGenre != -1 && genreCount.getOrDefault(primaryGenre, 0) >= MAX_PER_GENRE_CLUSTER) continue;
			shelf.add(r);
			if (primaryGenre != -1) genreCount.merge(primaryGenre, 1, Integer::sum);
			if (shelf.size() >= MAX_SHELF_SIZE) break;
		}
		for (TitleSearchResponse r : heldBack) {
			if (shelf.size() >= MIN_SHELF_SIZE) break;
			int primaryGenre = primaryGenre(r);
			if (primaryGenre != -1 && genreCount.getOrDefault(primaryGenre, 0) >= MAX_PER_GENRE_CLUSTER) continue;
			shelf.add(r);
			toppedUpIds.add(r.externalId());
			if (primaryGenre != -1) genreCount.merge(primaryGenre, 1, Integer::sum);
		}
		return shelf;
	}

	private int primaryGenre(TitleSearchResponse r) {
		List<Integer> genres = r.genreIds() != null ? r.genreIds() : List.of();
		return genres.isEmpty() ? -1 : genres.get(0);
	}

	private double genreScore(TitleSearchResponse candidate, Map<Integer, Double> genreProfile) {
		List<Integer> genres = candidate.genreIds();
		if (genres == null || genres.isEmpty()) return 0.0;
		return genres.stream().mapToDouble(id -> genreProfile.getOrDefault(id, 0.0)).sum();
	}

	private Map<Integer, Double> buildGenreProfile(
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId
	) {
		Map<Integer, Double> profile = new HashMap<>();
		for (WatchlistEntry e : entries) {
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(t.getExternalId());
			if (cached == null || cached.getGenreIds() == null) continue;
			double weight = profileWeight(e.getStatus());
			for (int genreId : cached.getGenreIds()) {
				profile.merge(genreId, weight, Double::sum);
			}
		}
		return profile;
	}

	private Set<Integer> buildKeywordProfile(Collection<TmdbTitleCache> caches) {
		Map<Integer, Integer> freq = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			if (c.getKeywordIds() == null) continue;
			for (int kw : c.getKeywordIds()) freq.merge(kw, 1, Integer::sum);
		}
		return freq.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
			.limit(MAX_KEYWORDS)
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	// Taste-profile weights only. Seed eligibility is a separate filter in compute
	// (WATCHING / WANT_TO_WATCH): finished titles shape the profile — finishing is
	// the strongest completed-interest signal — but don't get per-seed shelves
	private int profileWeight(WatchStatus status) {
		return switch (status) {
			case WATCHING, WATCHED -> 2;
			case WANT_TO_WATCH -> 1;
		};
	}
}
