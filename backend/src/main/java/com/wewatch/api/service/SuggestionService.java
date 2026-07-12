package com.wewatch.api.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.tmdb.TmdbClient;

/**
 * Builds the per-watchlist suggestion shelves. This class owns the cache and the
 * order of the pipeline; the stages themselves live in package-private
 * collaborators ({@link TasteProfileBuilder}, {@link CandidateScorer},
 * {@link ShelfFiller}, and the four shelf builders), which are constructed here
 * rather than injected so this constructor stays the single wiring point.
 *
 * <p>Stage order is behavior, not style. Shelves are built strongest-first
 * because they share one dedup set — the earlier a stage runs, the better its
 * pick of candidates — and they share one day-seeded {@link Random}, so the
 * sequence of draws determines what every user sees. See {@link SuggestionContext}.
 */
@Service
public class SuggestionService {

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final WatchlistMemberRepository watchlistMemberRepository;
	private final TitleService titleService;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;
	private final SuggestionImpressionService suggestionImpressionService;
	private final SuggestionDismissalService suggestionDismissalService;
	private final TitleRatingService titleRatingService;
	private final Clock clock;

	private final TasteProfileBuilder profileBuilder;
	private final ProviderContextResolver providerResolver;
	private final FranchiseShelfBuilder franchiseShelves;
	private final SeedShelfBuilder seedShelves;
	private final GenreShelfBuilder genreShelves;
	private final ExplorationShelfBuilder explorationShelves;

	// In-process cache: assumes a single backend instance. If the app ever scales
	// horizontally, each node caches (and invalidates) independently, so recompute
	// on one node won't refresh shelves served by another — move to a shared store then.
	private final Cache<Long, List<SuggestionShelfResponse>> cache;

	public SuggestionService(
		WatchlistEntryRepository watchlistEntryRepository,
		WatchlistMemberRepository watchlistMemberRepository,
		UserRepository userRepository,
		TitleService titleService,
		TmdbClient tmdbClient,
		TmdbTitleCacheRepository tmdbTitleCacheRepository,
		SuggestionImpressionService suggestionImpressionService,
		SuggestionDismissalService suggestionDismissalService,
		TitleRatingService titleRatingService,
		Clock clock,
		// The hand-tuned taste-profile constants (#288): profile weights, signal
		// boosts, jitter shape, recency decay, and the positional recency demotion.
		// Extracted to a @ConfigurationProperties class so the offline tuning
		// harness (and production config) can inject a different set; defaults are
		// the historical constant values, so unset properties change nothing.
		SuggestionTuningProperties tuning,
		@Value("${suggestions.cache.ttl-minutes}") long cacheTtlMinutes,
		@Value("${suggestions.cache.max-size}") long cacheMaxSize
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.watchlistMemberRepository = watchlistMemberRepository;
		this.titleService = titleService;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
		this.suggestionImpressionService = suggestionImpressionService;
		this.suggestionDismissalService = suggestionDismissalService;
		this.titleRatingService = titleRatingService;
		this.clock = clock;

		CandidateScorer scorer = new CandidateScorer(tmdbTitleCacheRepository, tuning);
		ShelfFiller filler = new ShelfFiller(tuning);
		this.profileBuilder = new TasteProfileBuilder(clock, tuning);
		this.providerResolver = new ProviderContextResolver(userRepository, tmdbTitleCacheRepository);
		this.franchiseShelves = new FranchiseShelfBuilder(tmdbClient, filler);
		this.seedShelves = new SeedShelfBuilder(tmdbClient, scorer, filler);
		this.genreShelves = new GenreShelfBuilder(tmdbClient, filler);
		this.explorationShelves = new ExplorationShelfBuilder(tmdbClient, scorer, filler);

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

	// A dismissal (#268) changes what every one of the user's lists may serve, so
	// drop each of their cached shelf sets and let the next read recompute lazily —
	// cheaper than recomputing all lists eagerly on every dismissal, and the client
	// already removed the tile optimistically. Undo evicts through here too so the
	// title can come back.
	public void evictForUser(Long userId) {
		watchlistMemberRepository.findWatchlistIdsByUserId(userId).forEach(cache::invalidate);
	}

	private List<SuggestionShelfResponse> compute(Long watchlistId) {
		SuggestionContext ctx = loadContext(watchlistId);
		if (ctx == null) return List.of();

		List<SuggestionShelfResponse> shelves = new ArrayList<>();

		// Franchise continuation is the highest-precision suggestion class
		// available: a remaining part of a series the user already started. Built
		// first so it gets first pick of the seen-dedup set, ahead of generic
		// per-seed, genre, and exploration candidates (#272).
		SuggestionShelfResponse franchise = franchiseShelves.build(ctx);
		if (franchise != null) shelves.add(franchise);

		shelves.addAll(seedShelves.build(ctx));
		shelves.addAll(genreShelves.build(ctx));
		shelves.addAll(explorationShelves.build(ctx));

		// Availability badges (#270): annotate served titles with which of the
		// members' services carry them, from cached provider data
		List<SuggestionShelfResponse> badged = providerResolver.attachBadges(shelves, ctx.providers());

		recordImpressions(badged, ctx.memberUserIds());
		return badged;
	}

	// Gathers everything the stages read. Returns null for an empty watchlist —
	// there is nothing to suggest from.
	private SuggestionContext loadContext(Long watchlistId) {
		List<WatchlistEntry> allEntries = watchlistEntryRepository
			.findByWatchlistId(watchlistId, null, Pageable.unpaged())
			.getContent();

		if (allEntries.isEmpty()) return null;

		List<Long> titleIds = allEntries.stream().map(WatchlistEntry::getTitleId).toList();
		Map<Long, Title> titlesById = titleService.findByIds(titleIds);

		Set<String> ownedExternalIds = allEntries.stream()
			.map(WatchlistEntry::getExternalId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		// Load cache entries once for genre + keyword + person profile building
		Map<String, TmdbTitleCache> cacheByTmdbId = tmdbTitleCacheRepository.findAllById(ownedExternalIds)
			.stream().collect(Collectors.toMap(TmdbTitleCache::getTmdbId, c -> c));

		// The recency penalty follows the user, not the list (#247): a shelf answers
		// to every member's impressions, so a title one member saw elsewhere sinks
		// here too. For a personal list this is just the single owner. Resolved
		// before profile building because ratings (#273) fold in per member.
		List<Long> memberUserIds = watchlistMemberRepository.findUserIdsByWatchlistId(watchlistId);

		LocalDate today = LocalDate.now(clock);

		// The net thumbs rating across members per title (#273) — conflicting
		// up/down cancel to unrated, the documented shared-list choice
		Map<Long, Rating> ratingsByTitleId = titleRatingService.effectiveRatings(memberUserIds, titleIds);

		TasteProfile profile =
			profileBuilder.build(allEntries, titlesById, cacheByTmdbId, ratingsByTitleId, today);

		// Cross-shelf dedup: start from all owned externalIds.
		// "Not interested" dismissals (#268) are a hard exclusion, not a penalty:
		// seeding the dedup set keeps a dismissed title out of every shelf kind at
		// the one point they all flow through. Union of members, like the recency
		// penalty — dismissals are personal but shelves are per-watchlist.
		Set<String> seen = new HashSet<>(ownedExternalIds);
		seen.addAll(suggestionDismissalService.dismissedTmdbIds(memberUserIds));

		// Titles shown on previous days within the penalty window sink in the ranking
		// instead of being held back outright (#264): thin pools still fill a whole
		// shelf, and recently shown titles rotate out by penalty decay + jitter rather
		// than boomeranging back the day a binary suppression window expires.
		Map<String, Double> recencyWeights = suggestionImpressionService.recencyWeights(memberUserIds);

		// Seeded on (watchlist, calendar day): shelves are stable across recomputes
		// within a day and rotate at midnight for free (#231)
		Random rng = new Random(Objects.hash(watchlistId, today.toEpochDay()));

		return new SuggestionContext(today, memberUserIds, allEntries, titlesById, cacheByTmdbId,
			profile, providerResolver.resolve(memberUserIds), seen, recencyWeights, rng);
	}

	// Record everything we're about to serve so tomorrow's compute penalizes it
	// (#264). Re-served titles are recorded too — safe under a continuous penalty,
	// they just start sinking again, where the binary window needed re-records
	// excluded to keep top-ups from being trapped forever (#246).
	private void recordImpressions(List<SuggestionShelfResponse> shelves, List<Long> memberUserIds) {
		Set<String> shownIds = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.collect(Collectors.toSet());
		suggestionImpressionService.recordShown(memberUserIds, shownIds);
	}
}
