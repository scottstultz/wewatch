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
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
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
	// A standalone seed shelf must carry this many fresh (not recency-penalized)
	// titles (#266) — anything thinner reads as a stub next to full shelves and
	// exhausts within one penalty window. Shelves under the floor fold their
	// candidates into the catch-all MORE_PICKS shelf instead.
	private static final int SEED_SHELF_MIN_FRESH = 6;
	// Vote-count floor for the rich seed tier (#266): a cheap proxy for whether
	// TMDB can sustain a full recommendations/similar shelf for the seed. Niche
	// titles (small docs, festival releases) sit well below it; anything with a
	// mainstream audience sits well above.
	private static final int RICH_SEED_VOTE_COUNT_GTE = 300;
	// Same-genre run cap for feeds with a real genre mix (recommendations/similar/
	// trending). Discover-backed feeds are exempt (#265): they are filtered to the
	// user's top genres by construction, so nearly every candidate shares a genre
	// and the cap would chop a 20-result page to ~4 before the shelf fills.
	private static final int MAX_PER_GENRE_CLUSTER = 4;
	private static final int SIMILAR_TOP_UP_THRESHOLD = 5;
	private static final int DISCOVER_VOTE_COUNT_GTE = 100;
	// Per-feed page depth (#249). The daily draw rotates a single page within these
	// bounds — deeper bounds mean the pool the draw can reach before repeating within
	// the suppression window, not more calls per compute.
	// Recommendations/similar genuinely run out after a few pages, so seeds stay shallow.
	private static final int MAX_SEED_FETCH_PAGE = 3;
	// Discover result sets are deep; pages 1–6 stay relevant with the page-1 fallback.
	private static final int MAX_DISCOVER_FETCH_PAGE = 6;
	// Trending/week thins into obscurity fast, so keep its draw shallow.
	private static final int MAX_TRENDING_FETCH_PAGE = 3;
	// Hidden gems draw from a mid-deep band so the shelf skips the static top-rated head
	// (pages 1–3 of vote_average.desc are identical for everyone with the same genres)
	// without reaching the emptiest deep pages that just trigger the page-1 fallback.
	private static final int HIDDEN_GEM_MIN_FETCH_PAGE = 4;
	private static final int HIDDEN_GEM_MAX_FETCH_PAGE = 18;
	// A single keyword's catalog is far thinner than a genre's — niche themes run
	// out within a few pages — so the keyword shelf (#271) draws shallow like the
	// seed feeds; its main rotation lever is which keyword the day picks anyway.
	private static final int MAX_KEYWORD_FETCH_PAGE = 3;
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
	// People-affinity signal (#269). A shared top-billed actor or director is the
	// sharpest per-title signal in the profile — sharper than a keyword (2.0) and
	// any single genre weight — but proportional jitter (#267) scales with it, so
	// it biases rather than pins the ranking.
	private static final double PERSON_MATCH_WEIGHT = 3.0;
	// Streamability boost (#270) for candidates on feeds that can't take TMDB's
	// provider filter (recommendations/similar/pooled). Below the person weight
	// (availability is orthogonal to taste and shouldn't outrank the strongest
	// taste signal) but above a keyword: a suggestion the user can actually
	// press play on beats a marginally better-matched one they can't.
	private static final double STREAMABLE_BOOST = 2.5;
	private static final int MAX_PEOPLE = 5;
	// One appearance is just a cast list; recurring across the user's titles is
	// the "you keep watching this person" signal the shelf label claims
	private static final int PERSON_MIN_TITLE_COUNT = 2;
	// A filmography includes shorts and bit parts; a modest floor keeps the
	// person shelf to titles a general audience has actually seen
	private static final int PERSON_SHELF_VOTE_COUNT_GTE = 50;
	// Day-seeded ± offset added to each candidate's score so ranking rotates daily
	// beyond exact ties (#248). Proportional to the candidate's base score (#267):
	// rotation reorders near-peers while a clearly dominant score (2× the runner-up)
	// stays on top every day, and the amplitude tracks the score scale by itself —
	// profile weights are unnormalized sums, so scores grow with the watchlist and
	// a flat amplitude was noise-dominant on sparse profiles and negligible on rich
	// ones. The floor keeps zero/low-score candidates rotating among themselves
	// without ever lifting them over a real (≥1) genre match.
	private static final double SCORE_JITTER_FRACTION = 0.15;
	private static final double SCORE_JITTER_FLOOR = 0.25;
	// Soft recency penalty (#264), replacing #233's binary suppression. Applied in
	// fillShelf — the one point both score-ranked seed feeds and order-only discover
	// feeds flow through — as a positional demotion: a title shown yesterday sinks
	// this many positions (past a full shelf, so it resurfaces only when the pool is
	// thin), decaying linearly to ~2 positions at the window edge.
	private static final double RECENCY_DEMOTION = 16.0;

	private final WatchlistEntryRepository watchlistEntryRepository;
	private final WatchlistMemberRepository watchlistMemberRepository;
	private final UserRepository userRepository;
	private final TitleService titleService;
	private final TmdbClient tmdbClient;
	private final TmdbTitleCacheRepository tmdbTitleCacheRepository;
	private final SuggestionImpressionService suggestionImpressionService;
	private final SuggestionDismissalService suggestionDismissalService;
	private final Clock clock;

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
		Clock clock,
		@Value("${suggestions.cache.ttl-minutes}") long cacheTtlMinutes,
		@Value("${suggestions.cache.max-size}") long cacheMaxSize
	) {
		this.watchlistEntryRepository = watchlistEntryRepository;
		this.watchlistMemberRepository = watchlistMemberRepository;
		this.userRepository = userRepository;
		this.titleService = titleService;
		this.tmdbClient = tmdbClient;
		this.tmdbTitleCacheRepository = tmdbTitleCacheRepository;
		this.suggestionImpressionService = suggestionImpressionService;
		this.suggestionDismissalService = suggestionDismissalService;
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

	// A dismissal (#268) changes what every one of the user's lists may serve, so
	// drop each of their cached shelf sets and let the next read recompute lazily —
	// cheaper than recomputing all lists eagerly on every dismissal, and the client
	// already removed the tile optimistically. Undo evicts through here too so the
	// title can come back.
	public void evictForUser(Long userId) {
		watchlistMemberRepository.findWatchlistIdsByUserId(userId).forEach(cache::invalidate);
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
		// Top keywords across the list: ids boost candidate scores, and the ones
		// with a cached display name can seed a keyword shelf (#271)
		List<KeywordAffinity> keywordAffinities = buildKeywordAffinities(cacheByTmdbId.values());
		Set<Integer> keywordProfile = keywordAffinities.stream()
			.map(KeywordAffinity::id)
			.collect(Collectors.toSet());
		// Recurring cast/directors across the list (#269): ids boost candidate
		// scores, names label the person-seeded exploration shelf
		List<PersonAffinity> personProfile = buildPersonProfile(cacheByTmdbId.values());
		Set<Integer> personIdProfile = personProfile.stream()
			.map(PersonAffinity::id)
			.collect(Collectors.toSet());

		// Cross-shelf dedup: start from all owned externalIds
		Set<String> seen = new HashSet<>(ownedExternalIds);

		// The recency penalty follows the user, not the list (#247): a shelf answers
		// to every member's impressions, so a title one member saw elsewhere sinks
		// here too. For a personal list this is just the single owner.
		List<Long> memberUserIds = watchlistMemberRepository.findUserIdsByWatchlistId(watchlistId);

		// Watch-provider context (#270): which streaming services this shelf set
		// may assume, and in which region. Union across members with a settings
		// bearing on it; disabled entirely when regions conflict or nobody has
		// configured services — behavior is then exactly the pre-#270 one.
		ProviderContext providerCtx = providerContext(memberUserIds);

		// "Not interested" dismissals (#268) are a hard exclusion, not a penalty:
		// seeding the dedup set keeps a dismissed title out of every shelf kind at
		// the one point they all flow through. Union of members, like the recency
		// penalty above — dismissals are personal but shelves are per-watchlist.
		seen.addAll(suggestionDismissalService.dismissedTmdbIds(memberUserIds));

		// Titles shown on previous days within the penalty window sink in the ranking
		// instead of being held back outright (#264): thin pools still fill a whole
		// shelf, and recently shown titles rotate out by penalty decay + jitter rather
		// than boomeranging back the day a binary suppression window expires.
		Map<String, Double> recencyWeights = suggestionImpressionService.recencyWeights(memberUserIds);

		List<SuggestionShelfResponse> shelves = new ArrayList<>();

		// Seeded on (watchlist, calendar day): shelves are stable across recomputes
		// within a day and rotate at midnight for free (#231)
		Random rng = new Random(Objects.hash(watchlistId, LocalDate.now(clock).toEpochDay()));

		// ── Franchise-continuation shelf (#272) ───────────────────
		// Franchise continuation is the highest-precision suggestion class
		// available: a remaining part of a series the user already started.
		// Built first so it gets first pick of the seen-dedup set, ahead of
		// generic per-seed/genre/exploration candidates. Rotates across
		// qualifying collections daily like the exploration kinds, but isn't
		// capped by MAX_EXPLORATION_SHELVES or gated by MIN_SHELF_SIZE — even a
		// single remaining sequel outranks a full shelf of generic candidates.
		SuggestionShelfResponse franchiseShelf =
			buildFranchiseShelf(allEntries, titlesById, cacheByTmdbId, seen, recencyWeights, rng);
		if (franchiseShelf != null) {
			shelves.add(franchiseShelf);
		}

		// ── Per-seed shelves ───────────────────────────────────────
		// Sort by id before shuffling: repository ordering isn't guaranteed, and the
		// shuffle must see the same input order to be reproducible within a day
		List<WatchlistEntry> eligibleSeeds = allEntries.stream()
			.filter(e -> e.getStatus() == WatchStatus.WATCHING || e.getStatus() == WatchStatus.WANT_TO_WATCH)
			.filter(e -> titlesById.containsKey(e.getTitleId()))
			.sorted(Comparator.comparing(WatchlistEntry::getId))
			.toList();

		// Rich-first seed selection (#266): cached vote count proxies how deep a
		// title's recommendations/similar feeds run — niche seeds yield permanently
		// thin shelves. Each tier is shuffled with the day-seeded rng, so rich seeds
		// rotate among themselves rather than pinning to the top-popularity few, and
		// thin seeds only fill slots the rich tier can't.
		List<WatchlistEntry> richSeeds = new ArrayList<>();
		List<WatchlistEntry> thinSeeds = new ArrayList<>();
		for (WatchlistEntry e : eligibleSeeds) {
			(isRichSeed(e, titlesById, cacheByTmdbId) ? richSeeds : thinSeeds).add(e);
		}
		Collections.shuffle(richSeeds, rng);
		Collections.shuffle(thinSeeds, rng);
		List<WatchlistEntry> seeds = new ArrayList<>(richSeeds);
		seeds.addAll(thinSeeds);
		seeds = seeds.subList(0, Math.min(MAX_SEEDS, seeds.size()));

		// Candidates from seed shelves that miss the fresh floor pool here for the
		// catch-all MORE_PICKS shelf (#266)
		List<TitleSearchResponse> pooledLeftovers = new ArrayList<>();

		for (WatchlistEntry seed : seeds) {
			Title title = titlesById.get(seed.getTitleId());
			if (title == null || title.getExternalId() == null) continue;

			List<TitleSearchResponse> candidates = fetchScoredCandidates(title.getType(), title.getExternalId(), genreProfile, keywordProfile, personIdProfile, providerCtx, rng);
			List<TitleSearchResponse> shelf = fillShelf(candidates, seen, recencyWeights, true);

			if (freshCount(shelf, recencyWeights) >= SEED_SHELF_MIN_FRESH) {
				String label = title.getName() != null
					? "Because you added " + title.getName()
					: "Because of your list";
				shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.PER_SEED));
			} else {
				// Too thin to stand alone (#266): release the slots this shelf
				// claimed so its candidates can compete in the catch-all instead
				shelf.forEach(r -> seen.remove(r.externalId()));
				pooledLeftovers.addAll(candidates);
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

			List<TitleSearchResponse> candidates = fetchScoredCandidates(title.getType(), title.getExternalId(), genreProfile, keywordProfile, personIdProfile, providerCtx, rng);
			List<TitleSearchResponse> shelf = fillShelf(candidates, seen, recencyWeights, true);

			if (freshCount(shelf, recencyWeights) >= SEED_SHELF_MIN_FRESH) {
				String label = title.getName() != null
					? "Because you finished " + title.getName()
					: "Because you finished a title";
				shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.FINISHED_SEED));
			} else {
				shelf.forEach(r -> seen.remove(r.externalId()));
				pooledLeftovers.addAll(candidates);
			}
		}

		// ── Catch-all shelf (#266) ────────────────────────────────
		// Seed feeds too thin for a standalone shelf pool their candidates into one
		// "More picks for you" shelf, re-ranked as a single taste-profile-scored
		// pool — a handful of full shelves plus one aggregate instead of a row of
		// 3–4 tile stubs
		if (!pooledLeftovers.isEmpty()) {
			List<TitleSearchResponse> pooled = rankByTasteProfile(pooledLeftovers, genreProfile, keywordProfile, personIdProfile, providerCtx, rng);
			List<TitleSearchResponse> shelf = fillShelf(pooled, seen, recencyWeights, true);
			if (shelf.size() >= MIN_SHELF_SIZE) {
				shelves.add(new SuggestionShelfResponse("More picks for you", shelf, SuggestionShelfResponse.ShelfKind.MORE_PICKS));
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
			int discoverPage = 1 + rng.nextInt(MAX_DISCOVER_FETCH_PAGE);
			try {
				// Discover takes TMDB's provider filter directly (#270): with a
				// provider context, everything on this shelf is streamable on the
				// members' services
				List<TitleSearchResponse> discovered = fetchPageWithFallback(
					p -> tmdbClient.discover(type, topGenres, typeKeywords, DISCOVER_VOTE_COUNT_GTE,
						SORT_POPULARITY, null, null, providerCtx.region(), providerCtx.providerIdList(), p), discoverPage);
				// No genre diversification (#265): this feed is filtered to topGenres
				List<TitleSearchResponse> shelf = fillShelf(discovered, seen, recencyWeights, false);
				if (shelf.size() >= MIN_SHELF_SIZE) {
					shelves.add(new SuggestionShelfResponse(label, shelf, SuggestionShelfResponse.ShelfKind.GENRE_PROFILE, providerCtx.enabled()));
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
			SuggestionShelfResponse.ShelfKind.TRENDING,
			SuggestionShelfResponse.ShelfKind.PERSON,
			SuggestionShelfResponse.ShelfKind.KEYWORD));
		Collections.shuffle(explorationOrder, rng);

		int explorationCount = 0;
		for (SuggestionShelfResponse.ShelfKind kind : explorationOrder) {
			if (explorationCount >= MAX_EXPLORATION_SHELVES) break;
			SuggestionShelfResponse shelf = buildExplorationShelf(kind, dominantType, dominantGenres, genreProfile, personProfile, keywordAffinities, providerCtx, seen, recencyWeights, rng);
			if (shelf != null) {
				shelves.add(shelf);
				explorationCount++;
			}
		}

		// Availability badges (#270): annotate served titles with which of the
		// members' services carry them, from cached provider data
		List<SuggestionShelfResponse> badged = attachProviderBadges(shelves, providerCtx);

		// Record everything we're about to serve so tomorrow's compute penalizes it
		// (#264). Re-served titles are recorded too — safe under a continuous penalty,
		// they just start sinking again, where the binary window needed re-records
		// excluded to keep top-ups from being trapped forever (#246).
		Set<String> shownIds = badged.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.collect(Collectors.toSet());
		suggestionImpressionService.recordShown(memberUserIds, shownIds);

		return badged;
	}

	// The provider context a shelf set answers to (#270): the union of member
	// services, valid only when every configured member agrees on a region.
	// Availability is region-scoped, and TMDB's discover filter takes exactly
	// one watch_region — with members in different regions there is no single
	// truthful answer, so provider-awareness turns off for that list (documented
	// shared-list simplification; per-member shelves would be the real fix).
	private record ProviderContext(String region, Set<Integer> providerIds) {
		static final ProviderContext DISABLED = new ProviderContext(null, Set.of());

		boolean enabled() {
			return region != null && !providerIds.isEmpty();
		}

		// Discover wants a list (ordered, joinable); null when disabled so the
		// TMDB client skips the filter entirely
		List<Integer> providerIdList() {
			return enabled() ? List.copyOf(providerIds) : null;
		}
	}

	private ProviderContext providerContext(List<Long> memberUserIds) {
		List<User> configured = userRepository.findAllById(memberUserIds).stream()
			.filter(u -> u.getWatchRegion() != null
				&& u.getWatchProviderIds() != null && !u.getWatchProviderIds().isEmpty())
			.toList();
		if (configured.isEmpty()) return ProviderContext.DISABLED;

		Set<String> regions = configured.stream().map(User::getWatchRegion).collect(Collectors.toSet());
		if (regions.size() > 1) return ProviderContext.DISABLED;

		Set<Integer> union = configured.stream()
			.flatMap(u -> u.getWatchProviderIds().stream())
			.collect(Collectors.toSet());
		return new ProviderContext(regions.iterator().next(), union);
	}

	// Rewrites served titles with the intersection of their cached flatrate
	// providers (context region) and the members' services (#270). One batch
	// cache read for all shelves. Coverage is partial by design — candidates
	// without a tmdb_title_cache row keep a null providerIds ("unknown"), the
	// same tradeoff as the keyword/person boosts; the title detail page fills
	// the gap with live data.
	private List<SuggestionShelfResponse> attachProviderBadges(List<SuggestionShelfResponse> shelves, ProviderContext ctx) {
		if (!ctx.enabled() || shelves.isEmpty()) return shelves;

		List<String> ids = shelves.stream()
			.flatMap(s -> s.titles().stream())
			.map(TitleSearchResponse::externalId)
			.distinct()
			.toList();
		Map<String, List<Integer>> badgesById = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			List<Integer> mine = streamableOn(cached, ctx);
			if (!mine.isEmpty()) badgesById.put(cached.getTmdbId(), mine);
		}
		if (badgesById.isEmpty()) return shelves;

		return shelves.stream()
			.map(s -> new SuggestionShelfResponse(
				s.reason(),
				s.titles().stream()
					.map(t -> badgesById.containsKey(t.externalId())
						? new TitleSearchResponse(t.externalId(), t.externalSource(), t.type(), t.name(),
							t.overview(), t.releaseDate(), t.posterUrl(), t.genreIds(), badgesById.get(t.externalId()))
						: t)
					.toList(),
				s.kind(),
				s.providerFiltered()))
			.toList();
	}

	// The members' services carrying this cached title in the context region;
	// empty when unknown (no cache row data) or streamable nowhere relevant
	private List<Integer> streamableOn(TmdbTitleCache cached, ProviderContext ctx) {
		Map<String, List<Integer>> providers = cached.getWatchProviders();
		List<Integer> regionIds = providers != null ? providers.get(ctx.region()) : null;
		if (regionIds == null) return List.of();
		return regionIds.stream().filter(ctx.providerIds()::contains).toList();
	}

	// Exploration shelves (#235) trade similarity for discovery: recent releases,
	// well-rated titles outside the popularity head, and this week's trending —
	// still deduped and recency-demoted like every other shelf. Returns null when
	// the kind can't produce a shelf so the caller can try the next kind.
	private SuggestionShelfResponse buildExplorationShelf(
		SuggestionShelfResponse.ShelfKind kind,
		TitleType type,
		List<Integer> topGenres,
		Map<Integer, Double> genreProfile,
		List<PersonAffinity> personProfile,
		List<KeywordAffinity> keywordAffinities,
		ProviderContext providerCtx,
		Set<String> seen,
		Map<String, Double> recencyWeights,
		Random rng
	) {
		// Page draw is per-kind (#249): discover-backed kinds go deeper, hidden gems
		// draws from a mid-deep band, trending stays shallow, PERSON spends its draw
		// picking the person instead of a page, and KEYWORD draws a keyword plus a
		// shallow page. A fixed draw count per kind either way, so daily
		// reproducibility (#231/#248) is preserved.
		List<TitleSearchResponse> candidates;
		String label;
		// Discover-backed kinds take TMDB's provider filter (#270); trending and
		// person feeds don't support it and stay unfiltered
		boolean providerFiltered = providerCtx.enabled()
			&& (kind == SuggestionShelfResponse.ShelfKind.NEW_RELEASES
				|| kind == SuggestionShelfResponse.ShelfKind.HIDDEN_GEMS
				|| kind == SuggestionShelfResponse.ShelfKind.KEYWORD);
		try {
			switch (kind) {
				case NEW_RELEASES -> {
					if (topGenres.isEmpty()) return null;
					int page = 1 + rng.nextInt(MAX_DISCOVER_FETCH_PAGE);
					LocalDate today = LocalDate.now(clock);
					candidates = fetchPageWithFallback(p -> tmdbClient.discover(
						type, topGenres, List.of(), NEW_RELEASE_VOTE_COUNT_GTE,
						SORT_POPULARITY, today.minusDays(NEW_RELEASE_WINDOW_DAYS), today,
						providerCtx.region(), providerCtx.providerIdList(), p), page);
					label = "New in your genres";
				}
				case HIDDEN_GEMS -> {
					if (topGenres.isEmpty()) return null;
					int page = HIDDEN_GEM_MIN_FETCH_PAGE
						+ rng.nextInt(HIDDEN_GEM_MAX_FETCH_PAGE - HIDDEN_GEM_MIN_FETCH_PAGE + 1);
					candidates = fetchPageWithFallback(p -> tmdbClient.discover(
						type, topGenres, List.of(), HIDDEN_GEM_VOTE_COUNT_GTE,
						SORT_VOTE_AVERAGE, null, null,
						providerCtx.region(), providerCtx.providerIdList(), p), page);
					label = "Hidden gems";
				}
				case PERSON -> {
					// Movie-only (TMDB's TV discover has no people filter) and always
					// page 1 — a filmography is shallow; the daily rotation comes from
					// which recurring person the rng draws, not from page depth (#269)
					if (personProfile.isEmpty()) return null;
					PersonAffinity person = personProfile.get(rng.nextInt(personProfile.size()));
					candidates = tmdbClient.discoverByPerson(person.id(), PERSON_SHELF_VOTE_COUNT_GTE, 1);
					label = person.director()
						? "Directed by " + person.name()
						: "More with " + person.name();
				}
				case KEYWORD -> {
					// Only keywords with a cached display name qualify — the label
					// is the whole point (#271); rows cached before V20 contribute
					// scoring ids but no shelf until the TTL refresh backfills names
					List<KeywordAffinity> named = keywordAffinities.stream()
						.filter(k -> k.name() != null && !k.name().isBlank())
						.toList();
					if (named.isEmpty()) return null;
					KeywordAffinity keyword = named.get(rng.nextInt(named.size()));
					int page = 1 + rng.nextInt(MAX_KEYWORD_FETCH_PAGE);
					candidates = fetchPageWithFallback(p -> tmdbClient.discoverByKeyword(
						type, keyword.id(), DISCOVER_VOTE_COUNT_GTE,
						providerCtx.region(), providerCtx.providerIdList(), p), page);
					label = keywordLabel(keyword.name(), type);
				}
				case TRENDING -> {
					int page = 1 + rng.nextInt(MAX_TRENDING_FETCH_PAGE);
					// Rank the raw popularity feed by taste-profile affinity plus
					// day-seeded score-proportional jitter (#248/#267) so the order
					// rotates daily beyond ties; with no genre profile the floor
					// amplitude degrades the sort to stable-per-day random
					List<TitleSearchResponse> trending = new ArrayList<>(
						fetchPageWithFallback(p -> tmdbClient.getTrending(type, p), page));
					Map<String, Double> jitter = jitterByCandidate(trending, r -> genreScore(r, genreProfile), rng);
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

		// Only trending carries a real genre mix worth diversifying; the discover-backed
		// kinds are genre-filtered by construction and are exempt from the cap (#265).
		// PERSON and KEYWORD are exempt too: the person or theme is the shelf's
		// coherence axis, and a same-genre run is the point, not a lack of variety.
		boolean diversify = kind == SuggestionShelfResponse.ShelfKind.TRENDING;
		List<TitleSearchResponse> shelf = fillShelf(candidates, seen, recencyWeights, diversify);
		return shelf.size() >= MIN_SHELF_SIZE ? new SuggestionShelfResponse(label, shelf, kind, providerFiltered) : null;
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
	// affinity plus a boost per shared keyword and per shared person (#269).
	// These endpoints take no provider filter, so streamability enters as a
	// score boost instead (#270).
	private List<TitleSearchResponse> fetchScoredCandidates(TitleType type, String tmdbId, Map<Integer, Double> genreProfile, Set<Integer> keywordProfile, Set<Integer> personProfile, ProviderContext providerCtx, Random rng) {
		// One page draw per seed, shared by recommendations and similar, so RNG
		// consumption doesn't depend on how many results TMDB happens to return
		int page = 1 + rng.nextInt(MAX_SEED_FETCH_PAGE);
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
		return rankByTasteProfile(results, genreProfile, keywordProfile, personProfile, providerCtx, rng);
	}

	// Dedupe by externalId, then sort by taste-profile score: genre affinity plus
	// keyword boost plus day-seeded score jitter (#248) — a score-proportional ±
	// offset per candidate (#267) that rotates the ranking of near-peers daily
	// beyond exact ties, so stable high-affinity candidates don't float to the top
	// of a shelf every single day. Applied even with no genre/keyword signal, where
	// the floor amplitude degrades the sort to a stable-per-day random order. Also
	// ranks the pooled catch-all shelf (#266).
	private List<TitleSearchResponse> rankByTasteProfile(List<TitleSearchResponse> results, Map<Integer, Double> genreProfile, Set<Integer> keywordProfile, Set<Integer> personProfile, ProviderContext providerCtx, Random rng) {
		Set<String> seen = new HashSet<>();
		List<TitleSearchResponse> deduped = results.stream()
			.filter(r -> seen.add(r.externalId()))
			.collect(Collectors.toCollection(ArrayList::new));

		Map<String, Double> cacheBoosts = cacheSignalBoosts(deduped, keywordProfile, personProfile, providerCtx);
		ToDoubleFunction<TitleSearchResponse> baseScore = r ->
			genreScore(r, genreProfile) + cacheBoosts.getOrDefault(r.externalId(), 0.0);
		Map<String, Double> jitter = jitterByCandidate(deduped, baseScore, rng);

		deduped.sort(Comparator.comparingDouble((TitleSearchResponse r) ->
			baseScore.applyAsDouble(r)
			+ jitter.getOrDefault(r.externalId(), 0.0)).reversed());
		return deduped;
	}

	// Fresh = not recency-penalized: the shelf titles the user hasn't been shown
	// within the penalty window. The standalone floor counts only these (#266),
	// so a shelf padded out with re-served titles still folds into the catch-all.
	private long freshCount(List<TitleSearchResponse> shelf, Map<String, Double> recencyWeights) {
		return shelf.stream()
			.filter(r -> recencyWeights.getOrDefault(r.externalId(), 0.0) == 0.0)
			.count();
	}

	// Uncached seeds (no tmdb_title_cache row yet, or a pre-#266 row without a
	// vote count) rank as thin until the cache refreshes — a conservative default
	private boolean isRichSeed(WatchlistEntry entry, Map<Long, Title> titlesById, Map<String, TmdbTitleCache> cacheByTmdbId) {
		Title title = titlesById.get(entry.getTitleId());
		if (title == null || title.getExternalId() == null) return false;
		TmdbTitleCache cached = cacheByTmdbId.get(title.getExternalId());
		return cached != null && cached.getVoteCount() != null
			&& cached.getVoteCount() >= RICH_SEED_VOTE_COUNT_GTE;
	}

	// Day-seeded per-candidate score offset (#248). Draws from the shared day-seeded
	// rng in list order, so the assignment is reproducible within a day (identical
	// shelves across recomputes) and rotates at midnight. Keyed by externalId, one
	// draw per distinct id so duplicates don't desync the rng stream. The amplitude
	// is proportional to the candidate's base score with an absolute floor (#267);
	// scaling happens after the draw, so amplitude never affects rng consumption.
	private Map<String, Double> jitterByCandidate(
		List<TitleSearchResponse> candidates,
		ToDoubleFunction<TitleSearchResponse> baseScore,
		Random rng
	) {
		Map<String, Double> jitter = new HashMap<>();
		for (TitleSearchResponse c : candidates) {
			jitter.computeIfAbsent(c.externalId(), id -> {
				double amplitude = Math.max(SCORE_JITTER_FLOOR, SCORE_JITTER_FRACTION * baseScore.applyAsDouble(c));
				return (rng.nextDouble() * 2 - 1) * amplitude;
			});
		}
		return jitter;
	}

	// TMDB recommendations/similar responses carry no keywords, credits, or
	// provider data, so the keyword (#232), person (#269), and streamability
	// (#270) boosts all come from tmdb_title_cache in one batch read: candidates
	// without a cache row get none — a partial signal, but one DB read instead
	// of a TMDB call per candidate
	private Map<String, Double> cacheSignalBoosts(List<TitleSearchResponse> candidates, Set<Integer> keywordProfile, Set<Integer> personProfile, ProviderContext providerCtx) {
		if ((keywordProfile.isEmpty() && personProfile.isEmpty() && !providerCtx.enabled()) || candidates.isEmpty()) return Map.of();
		List<String> ids = candidates.stream().map(TitleSearchResponse::externalId).toList();
		Map<String, Double> boosts = new HashMap<>();
		for (TmdbTitleCache cached : tmdbTitleCacheRepository.findAllById(ids)) {
			double boost = 0.0;
			if (cached.getKeywordIds() != null) {
				boost += KEYWORD_MATCH_WEIGHT
					* cached.getKeywordIds().stream().filter(keywordProfile::contains).count();
			}
			// Set-union of cast and director ids: acting in and directing the same
			// title is one shared person, not two
			boost += PERSON_MATCH_WEIGHT
				* cachedPersonIds(cached).stream().filter(personProfile::contains).count();
			// Flat, not per-service (#270): being on two of the user's services
			// doesn't make a title more watchable than being on one
			if (providerCtx.enabled() && !streamableOn(cached, providerCtx).isEmpty()) {
				boost += STREAMABLE_BOOST;
			}
			if (boost > 0) boosts.put(cached.getTmdbId(), boost);
		}
		return boosts;
	}

	private Set<Integer> cachedPersonIds(TmdbTitleCache cached) {
		Set<Integer> ids = new HashSet<>();
		if (cached.getTopCast() != null) cached.getTopCast().forEach(p -> ids.add(p.id()));
		if (cached.getDirectors() != null) cached.getDirectors().forEach(p -> ids.add(p.id()));
		return ids;
	}

	// Deeper pages can be empty (few recommendations, thin discover results) — fall
	// back to page 1 rather than dropping the shelf for falling under MIN_SHELF_SIZE
	private List<TitleSearchResponse> fetchPageWithFallback(IntFunction<List<TitleSearchResponse>> fetch, int page) {
		List<TitleSearchResponse> results = fetch.apply(page);
		return results.isEmpty() && page > 1 ? fetch.apply(1) : results;
	}

	// Fill a shelf, optionally diversified: with diversify on, a candidate is skipped
	// once every genre it carries is at MAX_PER_GENRE_CLUSTER — keyed on the full
	// genre set, not TMDB's arbitrary first genre id (#265), so a title bringing any
	// fresh genre still enters.
	// Recently shown candidates (#264) are demoted, not held back: a stable re-sort
	// pushes each one down by RECENCY_DEMOTION positions scaled by its recency weight
	// (1.0 = shown yesterday, decaying to 1/window at the window edge). Shown-yesterday
	// sinks past a full shelf and resurfaces only when the pool is thin, so shelves
	// fill toward MAX_SHELF_SIZE instead of pinning at a suppression floor, while a
	// title near the window edge recovers most of its rank.
	private List<TitleSearchResponse> fillShelf(List<TitleSearchResponse> candidates, Set<String> seen, Map<String, Double> recencyWeights, boolean diversify) {
		List<TitleSearchResponse> ranked = IntStream.range(0, candidates.size()).boxed()
			.sorted(Comparator.comparingDouble((Integer i) ->
				i + RECENCY_DEMOTION * recencyWeights.getOrDefault(candidates.get(i).externalId(), 0.0)))
			.map(candidates::get)
			.toList();
		Map<Integer, Integer> genreCount = new HashMap<>();
		List<TitleSearchResponse> shelf = new ArrayList<>();
		for (TitleSearchResponse r : ranked) {
			if (!seen.add(r.externalId())) continue;
			List<Integer> genres = r.genreIds() != null ? r.genreIds() : List.of();
			if (diversify && allGenresSaturated(genres, genreCount)) continue;
			shelf.add(r);
			if (diversify) genres.forEach(g -> genreCount.merge(g, 1, Integer::sum));
			if (shelf.size() >= MAX_SHELF_SIZE) break;
		}
		return shelf;
	}

	// Genre-less candidates are never capped, matching the old primary-genre rule
	private boolean allGenresSaturated(List<Integer> genres, Map<Integer, Integer> genreCount) {
		return !genres.isEmpty()
			&& genres.stream().allMatch(g -> genreCount.getOrDefault(g, 0) >= MAX_PER_GENRE_CLUSTER);
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

	// A recurring person across the user's titles (#269). director flags which
	// label the shelf gets ("Directed by" vs "More with") when the same person
	// does both; titleCount drives the top-N cut like keyword frequency does.
	private record PersonAffinity(int id, String name, int titleCount, boolean director) {}

	// Frequency-weighted like the keyword profile, but floored at
	// PERSON_MIN_TITLE_COUNT: keywords are meaningful once, a person only through
	// recurrence. Ties break by id so the profile is stable across recomputes —
	// the daily rng never touches profile construction (#231).
	private List<PersonAffinity> buildPersonProfile(Collection<TmdbTitleCache> caches) {
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, Integer> castCounts = new HashMap<>();
		Map<Integer, Integer> directorCounts = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			if (c.getTopCast() != null) {
				for (CachedPerson p : c.getTopCast()) {
					castCounts.merge(p.id(), 1, Integer::sum);
					names.putIfAbsent(p.id(), p.name());
				}
			}
			if (c.getDirectors() != null) {
				for (CachedPerson p : c.getDirectors()) {
					directorCounts.merge(p.id(), 1, Integer::sum);
					names.putIfAbsent(p.id(), p.name());
				}
			}
		}
		return names.keySet().stream()
			.map(id -> {
				int cast = castCounts.getOrDefault(id, 0);
				int directed = directorCounts.getOrDefault(id, 0);
				return new PersonAffinity(id, names.get(id), cast + directed,
					directed > 0 && directed >= cast);
			})
			.filter(p -> p.titleCount() >= PERSON_MIN_TITLE_COUNT)
			.sorted(Comparator.comparingInt(PersonAffinity::titleCount).reversed()
				.thenComparing(PersonAffinity::id))
			.limit(MAX_PEOPLE)
			.toList();
	}

	// A top keyword across the list (#271). name labels the keyword-seeded shelf
	// and is null on rows cached before names were stored — such keywords still
	// boost scoring but can't seed a shelf until the cache TTL backfills them.
	private record KeywordAffinity(int id, String name) {}

	// Frequency counting stays on the flat keyword_ids column (every row has it);
	// names come from the keywords JSON where present. Ties break by id so the
	// profile is stable across recomputes — the daily rng never touches profile
	// construction (#231).
	private List<KeywordAffinity> buildKeywordAffinities(Collection<TmdbTitleCache> caches) {
		Map<Integer, Integer> freq = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		for (TmdbTitleCache c : caches) {
			if (c.getKeywordIds() == null) continue;
			for (int kw : c.getKeywordIds()) freq.merge(kw, 1, Integer::sum);
			if (c.getKeywords() != null) {
				c.getKeywords().forEach(k -> names.putIfAbsent(k.id(), k.name()));
			}
		}
		return freq.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
				.thenComparing(Map.Entry::getKey))
			.limit(MAX_KEYWORDS)
			.map(e -> new KeywordAffinity(e.getKey(), names.get(e.getKey())))
			.toList();
	}

	// A TMDB collection the user has started (#272): id feeds the /collection/{id}
	// call, name labels the shelf.
	private record FranchiseCandidate(int collectionId, String collectionName) {}

	// WATCHED/WATCHING movie entries with a cached collection id (#272).
	// WANT_TO_WATCH is excluded — franchise continuation is a completed-interest
	// signal, not a taste-profile one. Ties break by id so the daily rng never
	// touches candidate construction (#231), matching the person/keyword profiles.
	private List<FranchiseCandidate> buildFranchiseCandidates(
		List<WatchlistEntry> entries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId
	) {
		Map<Integer, String> collections = new HashMap<>();
		for (WatchlistEntry e : entries) {
			if (e.getStatus() != WatchStatus.WATCHED && e.getStatus() != WatchStatus.WATCHING) continue;
			Title t = titlesById.get(e.getTitleId());
			if (t == null || t.getType() != TitleType.MOVIE || t.getExternalId() == null) continue;
			TmdbTitleCache cached = cacheByTmdbId.get(t.getExternalId());
			if (cached == null || cached.getCollectionId() == null) continue;
			collections.putIfAbsent(cached.getCollectionId(), cached.getCollectionName());
		}
		return collections.entrySet().stream()
			.map(en -> new FranchiseCandidate(en.getKey(), en.getValue()))
			.sorted(Comparator.comparingInt(FranchiseCandidate::collectionId))
			.toList();
	}

	// One TMDB call when a qualifying collection exists, none otherwise (#272's
	// call-budget requirement). Parts are ordered by release date — the
	// franchise itself is the coherence axis, so no genre diversification and
	// no taste-profile re-ranking, just chronology. Owned parts fall out via the
	// shared seen-dedup set in fillShelf; no MIN_SHELF_SIZE floor, since a
	// single remaining sequel is still the single best suggestion available.
	private SuggestionShelfResponse buildFranchiseShelf(
		List<WatchlistEntry> allEntries,
		Map<Long, Title> titlesById,
		Map<String, TmdbTitleCache> cacheByTmdbId,
		Set<String> seen,
		Map<String, Double> recencyWeights,
		Random rng
	) {
		List<FranchiseCandidate> candidates = buildFranchiseCandidates(allEntries, titlesById, cacheByTmdbId);
		if (candidates.isEmpty()) return null;

		FranchiseCandidate franchise = candidates.get(rng.nextInt(candidates.size()));
		List<TitleSearchResponse> parts;
		try {
			parts = tmdbClient.getCollectionParts(franchise.collectionId());
		} catch (TmdbApiException e) {
			log.warn("Franchise shelf failed for collection {}: {}", franchise.collectionId(), e.getMessage());
			return null;
		}

		List<TitleSearchResponse> ordered = parts.stream()
			.sorted(Comparator.comparing(TitleSearchResponse::releaseDate,
				Comparator.nullsLast(Comparator.naturalOrder())))
			.toList();
		List<TitleSearchResponse> shelf = fillShelf(ordered, seen, recencyWeights, false);
		if (shelf.isEmpty()) return null;

		String name = franchise.collectionName() != null ? franchise.collectionName() : "this series";
		return new SuggestionShelfResponse("Next in the " + name, shelf, SuggestionShelfResponse.ShelfKind.FRANCHISE);
	}

	// "space race" → "Space race stories" / "heist" → "Heist movies": TMDB keyword
	// names are lowercase phrases, so capitalize and suffix by media type
	private String keywordLabel(String name, TitleType type) {
		String display = name.substring(0, 1).toUpperCase() + name.substring(1);
		return display + (type == TitleType.MOVIE ? " movies" : " stories");
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
