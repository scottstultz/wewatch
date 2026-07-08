package com.wewatch.api.tuning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wewatch.api.config.SuggestionTuningProperties;
import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.CachedKeyword;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.Rating;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.model.User;
import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;
import com.wewatch.api.repository.SuggestionImpressionRepository;
import com.wewatch.api.repository.TmdbTitleCacheRepository;
import com.wewatch.api.repository.UserRepository;
import com.wewatch.api.repository.WatchlistEntryRepository;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.repository.projection.SuggestionLastShown;
import com.wewatch.api.service.SuggestionDismissalService;
import com.wewatch.api.service.SuggestionImpressionService;
import com.wewatch.api.service.SuggestionService;
import com.wewatch.api.service.TitleRatingService;
import com.wewatch.api.service.TitleService;

/**
 * Assembles the offline collaborators for one fixture watchlist (#288) and
 * computes its shelves for a given parameter set and calendar day. The static
 * fixture data (entries, titles, cache rows, members, ratings, dismissals) is
 * built once; {@link #compute} spins up a fresh {@link SuggestionService} per
 * call so its internal cache never shadows a recompute, sharing the passed
 * {@link ImpressionStore} so a day-sweep accumulates the recency penalty the
 * way production would across days.
 *
 * Repository collaborators are Mockito stubs (only the handful of methods the
 * pipeline calls); the impression service runs for real over an in-memory
 * store, so the #264 decay math is exercised rather than faked.
 */
final class FixtureWorld {

	private static final ZoneOffset ZONE = ZoneOffset.UTC;
	private static final int SUPPRESSION_DAYS = 7;

	private final SyntheticCatalog catalog;
	private final FixtureWatchlist fixture;
	private final Map<String, List<TitleSearchResponse>> recordedByKey;

	private final long watchlistId;
	private final List<Long> memberIds;
	private final List<User> users;
	private final List<WatchlistEntry> entries;
	private final Map<Long, Title> titlesById;
	private final Map<String, TmdbTitleCache> ownedRows = new HashMap<>();
	private final Map<Long, Rating> effectiveRatings = new HashMap<>();
	private final Set<String> dismissed;
	private final Map<Integer, HarnessTmdbClient.CollectionData> collections = new HashMap<>();

	FixtureWorld(SyntheticCatalog catalog, FixtureWatchlist fixture,
			Map<String, List<TitleSearchResponse>> recordedByKey) {
		this.catalog = catalog;
		this.fixture = fixture;
		this.recordedByKey = recordedByKey;
		this.watchlistId = fixture.watchlistId();

		this.memberIds = fixture.members().stream().map(FixtureWatchlist.Member::userId).toList();
		this.users = fixture.members().stream().map(this::toUser).toList();
		this.dismissed = fixture.dismissed() != null ? new HashSet<>(fixture.dismissed()) : Set.of();

		this.entries = new ArrayList<>();
		this.titlesById = new HashMap<>();
		Map<Integer, List<TitleSearchResponse>> collectionParts = new HashMap<>();
		Map<Integer, String> collectionNames = new HashMap<>();
		for (FixtureWatchlist.Entry e : fixture.entries()) {
			TitleType type = TitleType.valueOf(e.type());
			Instant touched = BASE_INSTANT(e.ageDays());
			LocalDate release = e.releaseDate() != null ? LocalDate.parse(e.releaseDate()) : null;

			Title title = new Title(e.titleId(), e.tmdbId(), "tmdb", type, e.name(),
				null, release, null, touched, touched);
			titlesById.put(e.titleId(), title);

			WatchlistEntry entry = new WatchlistEntry();
			entry.setId(e.titleId());
			entry.setWatchlistId(watchlistId);
			entry.setTitleId(e.titleId());
			entry.setExternalId(e.tmdbId());
			entry.setExternalSource("tmdb");
			entry.setStatus(WatchStatus.valueOf(e.status()));
			entry.setAddedAt(touched);
			entry.setUpdatedAt(touched);
			entries.add(entry);

			ownedRows.put(e.tmdbId(), toCacheRow(e, type));

			if (e.rating() != null) {
				effectiveRatings.put(e.titleId(), Rating.valueOf(e.rating()));
			}
			if (e.collectionId() != null && type == TitleType.MOVIE) {
				collectionNames.putIfAbsent(e.collectionId(), e.collectionName());
				collectionParts.computeIfAbsent(e.collectionId(), k -> new ArrayList<>())
					.add(new TitleSearchResponse(e.tmdbId(), "tmdb", type, e.name(), null,
						release, null, e.genreIds() != null ? e.genreIds() : List.of()));
			}
		}
		collectionNames.forEach((id, name) ->
			collections.put(id, new HarnessTmdbClient.CollectionData(name, collectionParts.get(id))));
	}

	String name() {
		return fixture.name();
	}

	String description() {
		return fixture.description();
	}

	/** Shelves for this fixture under the given tuning, on the given day. */
	List<SuggestionShelfResponse> compute(SuggestionTuningProperties tuning, LocalDate day, ImpressionStore store) {
		Clock clock = Clock.fixed(day.atStartOfDay(ZONE).toInstant(), ZONE);

		WatchlistEntryRepository entryRepo = mock(WatchlistEntryRepository.class);
		when(entryRepo.findByWatchlistId(eq(watchlistId), any(), any()))
			.thenReturn(new org.springframework.data.domain.PageImpl<>(entries));

		WatchlistMemberRepository memberRepo = mock(WatchlistMemberRepository.class);
		when(memberRepo.findUserIdsByWatchlistId(watchlistId)).thenReturn(memberIds);

		UserRepository userRepo = mock(UserRepository.class);
		when(userRepo.findAllById(any())).thenReturn(users);

		TitleService titleService = mock(TitleService.class);
		when(titleService.findByIds(any())).thenReturn(titlesById);

		TmdbTitleCacheRepository cacheRepo = mock(TmdbTitleCacheRepository.class);
		when(cacheRepo.findAllById(any())).thenAnswer(inv -> {
			List<TmdbTitleCache> out = new ArrayList<>();
			for (String id : (Iterable<String>) inv.getArgument(0)) {
				TmdbTitleCache row = ownedRows.get(id);
				if (row == null) row = catalog.cacheRowFor(id);
				if (row != null) out.add(row);
			}
			return out;
		});

		TitleRatingService ratingService = mock(TitleRatingService.class);
		when(ratingService.effectiveRatings(any(), any())).thenReturn(effectiveRatings);

		SuggestionDismissalService dismissalService = mock(SuggestionDismissalService.class);
		when(dismissalService.dismissedTmdbIds(any())).thenReturn(dismissed);

		SuggestionImpressionService impressionService =
			new SuggestionImpressionService(impressionRepo(store), clock, SUPPRESSION_DAYS);

		HarnessTmdbClient tmdb = new HarnessTmdbClient(
			catalog, ownedRows::get, collections::get, recordedByKey);

		SuggestionService service = new SuggestionService(
			entryRepo, memberRepo, userRepo, titleService, tmdb, cacheRepo,
			impressionService, dismissalService, ratingService, clock, tuning, 30L, 1000L);

		return service.topPicks(watchlistId);
	}

	private SuggestionImpressionRepository impressionRepo(ImpressionStore store) {
		SuggestionImpressionRepository repo = mock(SuggestionImpressionRepository.class);
		when(repo.findLastShown(any(), any(), any())).thenAnswer(inv ->
			store.lastShown(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))
				.entrySet().stream()
				.map(e -> (SuggestionLastShown) new LastShown(e.getKey(), e.getValue()))
				.toList());
		doAnswer(inv -> {
			store.record(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(repo).insertImpression(any(), any(), any());
		doAnswer(inv -> {
			store.deleteBefore(inv.getArgument(0), inv.getArgument(1));
			return 0;
		}).when(repo).deleteShownBefore(any(), any());
		return repo;
	}

	private User toUser(FixtureWatchlist.Member m) {
		User u = new User();
		u.setId(m.userId());
		u.setEmail("member" + m.userId() + "@harness.test");
		if (m.region() != null) u.setWatchRegion(m.region());
		if (m.providerIds() != null) u.setWatchProviderIds(m.providerIds());
		return u;
	}

	private TmdbTitleCache toCacheRow(FixtureWatchlist.Entry e, TitleType type) {
		TmdbTitleCache row = new TmdbTitleCache();
		row.setTmdbId(e.tmdbId());
		row.setType(type == TitleType.MOVIE ? "MOVIE" : "TV");
		row.setName(e.name());
		row.setGenreIds(e.genreIds());
		row.setKeywordIds(e.keywordIds());
		if (e.keywordIds() != null) {
			row.setKeywords(e.keywordIds().stream()
				.map(id -> new CachedKeyword(id, catalog.keywordName(id)))
				.toList());
		}
		row.setVoteCount(e.voteCount());
		if (e.castIds() != null) {
			row.setTopCast(e.castIds().stream()
				.map(id -> new CachedPerson(id, catalog.personName(id))).toList());
		}
		if (e.directorIds() != null) {
			row.setDirectors(e.directorIds().stream()
				.map(id -> new CachedPerson(id, catalog.personName(id))).toList());
		}
		if (e.providerIds() != null && !e.providerIds().isEmpty()) {
			row.setWatchProviders(Map.of("US", e.providerIds()));
		}
		row.setCollectionId(e.collectionId());
		row.setCollectionName(e.collectionName());
		return row;
	}

	private static Instant BASE_INSTANT(int ageDays) {
		return SyntheticCatalog.BASE_DAY.minusDays(ageDays).atStartOfDay(ZONE).toInstant();
	}

	// Minimal projection impl backing the mocked impression repository
	private record LastShown(String tmdbId, LocalDate shownOn) implements SuggestionLastShown {
		@Override
		public String getTmdbId() {
			return tmdbId;
		}

		@Override
		public LocalDate getShownOn() {
			return shownOn;
		}
	}
}
