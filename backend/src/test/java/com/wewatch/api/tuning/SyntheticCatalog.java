package com.wewatch.api.tuning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.CachedKeyword;
import com.wewatch.api.model.CachedPerson;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbCacheKey;
import com.wewatch.api.model.TmdbTitleCache;

/**
 * A fixed, deterministic synthetic TMDB universe (#288): ~600 titles with
 * genres, keywords, people, vote stats, and streaming providers, generated
 * from per-index seeds so every run of the harness — on any machine, in any
 * order — sees the identical catalog. Feed methods answer requests the way
 * TMDB would (filter, sort, page at 20) over this universe, which keeps the
 * signals coherent: a candidate served by a feed has the same genres and
 * keywords in its synthetic cache row that the scoring pipeline later reads.
 *
 * Fixture watchlists reference the same genre/keyword/person id pools (see
 * the resource files under tuning/), so taste-profile boosts genuinely fire
 * against synthetic candidates. Recorded real-TMDB responses (FixtureStore)
 * take precedence over this catalog when present.
 */
final class SyntheticCatalog {

	/** Harness epoch: fixture timestamps and the default sweep start day are pinned to it. */
	static final LocalDate BASE_DAY = LocalDate.parse("2026-07-06");

	static final int UNIVERSE_SIZE = 600;
	private static final int PAGE_SIZE = 20;

	// Real TMDB genre ids so fixture files read naturally
	static final List<Integer> GENRE_POOL = List.of(
		18, 35, 80, 9648, 878, 53, 28, 12, 16, 10749, 99, 14);

	private static final String[] KEYWORD_NAMES = {
		"heist", "space opera", "serial killer", "time travel", "undercover",
		"dystopia", "small town", "con artist", "police procedural", "workplace comedy",
		"period drama", "anthology", "road trip", "haunted house", "courtroom",
		"artificial intelligence", "survival", "coming of age", "spy", "revenge",
		"post-apocalyptic", "political thriller", "cult", "amnesia", "based on a true story",
		"heist crew", "alien invasion", "noir", "family saga", "underdog",
		"conspiracy", "parallel universe", "assassin", "wilderness", "high school",
		"organized crime", "first contact", "redemption", "sibling rivalry", "deep space"
	};

	private static final String[] PEOPLE_NAMES = {
		"Mara Ellison", "Dev Okonkwo", "Sofia Reyes", "James Calloway", "Yuki Tanaka",
		"Anders Vik", "Priya Nair", "Tom Brandt", "Lena Fischer", "Marcus Cole",
		"Ines Duarte", "Viktor Hale", "Amara Diallo", "Noah Lindqvist", "Carmen Vega",
		"Elliot Shaw", "Hana Sato", "Ruben Ortiz", "Greta Muller", "Silas Kane",
		"Nadia Petrov", "Owen Gallagher", "Zara Hussain", "Felix Moreau", "Iris Novak",
		"Dante Russo", "Maeve O'Rourke", "Kofi Mensah", "Astrid Berg", "Julian Voss",
		"Rosa Delgado", "Theo Marchetti", "Leila Farhadi", "Casper Holm", "Bianca Toure",
		"Ezra Whitfield", "Mei-Lin Zhou", "Bruno Keller", "Sasha Volkov", "Tessa Byrne",
		"Idris Kamara", "Freya Dahl", "Rafael Quintero", "Petra Szabo", "Callum Reid",
		"Aiko Nakamura", "Stellan Bruhn", "Yara Haddad", "Miles Ashford", "Vera Antonova",
		"Jonas Weber", "Camille Roux", "Tariq El-Amin", "Sigrid Foss", "Declan Moore",
		"Lucia Ferrara", "Henrik Staal", "Amina Sesay", "Roman Kovac", "Edie Blackwood"
	};

	// US flatrate provider ids as TMDB uses them: Netflix, Prime Video, Hulu,
	// Disney+, Apple TV+, Max
	static final List<Integer> PROVIDER_POOL = List.of(8, 9, 15, 337, 350, 1899);

	private static final String[] NAME_ADJECTIVES = {
		"Crimson", "Silent", "Broken", "Golden", "Hollow", "Electric", "Savage", "Quiet",
		"Burning", "Lost", "Iron", "Velvet", "Pale", "Wild", "Frozen", "Neon",
		"Bitter", "Grand", "Restless", "Shattered", "Midnight", "Distant", "Radiant", "Feral"
	};
	private static final String[] NAME_NOUNS = {
		"Harbor", "Signal", "Empire", "Garden", "Protocol", "Reckoning", "Crown", "Divide",
		"Horizon", "Vault", "Covenant", "Machine", "Orchard", "Frontier", "Cartel", "Meridian",
		"Lantern", "Static", "Verdict", "Tide", "Compass", "Requiem", "Border", "Atlas"
	};

	record SynTitle(
		String tmdbId,
		TitleType type,
		String name,
		LocalDate releaseDate,
		List<Integer> genreIds,
		List<Integer> keywordIds,
		List<CachedPerson> topCast,
		List<CachedPerson> directors,
		int voteCount,
		double voteAverage,
		double popularity,
		List<Integer> usProviderIds
	) {}

	private final List<SynTitle> universe;
	private final Map<String, SynTitle> byId;
	private final Map<Integer, String> keywordNamesById;

	SyntheticCatalog() {
		this.keywordNamesById = new HashMap<>();
		for (int i = 0; i < KEYWORD_NAMES.length; i++) {
			keywordNamesById.put(100 + i, KEYWORD_NAMES[i]);
		}
		this.universe = new ArrayList<>(UNIVERSE_SIZE);
		for (int i = 0; i < UNIVERSE_SIZE; i++) {
			universe.add(generate(i));
		}
		this.byId = new HashMap<>();
		universe.forEach(t -> byId.put(t.tmdbId(), t));
	}

	// Everything about a title derives from its index seed — never from request
	// context — so the same id always carries the same attributes whichever feed
	// serves it
	private SynTitle generate(int i) {
		Random rng = new Random(7717L * (i + 1));
		TitleType type = i % 2 == 0 ? TitleType.TV : TitleType.MOVIE;

		Set<Integer> genres = new LinkedHashSet<>();
		int genreCount = 1 + rng.nextInt(3);
		while (genres.size() < genreCount) {
			genres.add(GENRE_POOL.get(rng.nextInt(GENRE_POOL.size())));
		}

		Set<Integer> keywords = new LinkedHashSet<>();
		int keywordCount = 2 + rng.nextInt(4);
		while (keywords.size() < keywordCount) {
			keywords.add(100 + rng.nextInt(KEYWORD_NAMES.length));
		}

		Set<Integer> castIds = new LinkedHashSet<>();
		int castCount = 3 + rng.nextInt(3);
		while (castIds.size() < castCount) {
			castIds.add(900 + rng.nextInt(PEOPLE_NAMES.length));
		}
		int directorId = 900 + rng.nextInt(PEOPLE_NAMES.length);

		// Log-distributed 20..20000 so the universe has a popularity head and a
		// long tail, like the real catalog
		int voteCount = (int) (20 * Math.pow(10, rng.nextDouble() * 3));
		double voteAverage = 5.0 + rng.nextDouble() * 4.5;
		// Scaled to TMDB's real popularity range (#376), which until now nothing
		// read as an absolute — every feed only ever *sorted* by it, so the old
		// 0..500 band was never calibrated. The hidden-gems ceiling compares
		// against the value itself, and against a 0..500 band the shipped ceiling
		// would reject ~96% of the universe: the shelf would never fill and the
		// fixture would read as a broken stage rather than an uncalibrated catalog.
		// 0..31.25 puts the median at 15.6, against 16.4 measured over live
		// vote_average.desc discover pages.
		//
		// The divisor MUST stay a power of two. Dividing by 16 is exact in binary
		// floating point, so every popularity-ordered feed — trending,
		// discoverByPerson, discoverByKeyword, and the popularity.desc discover
		// that feeds GenreShelfBuilder — sorts bit-identically to before. Genre
		// shelves build *above* the #376 insertion point, so a rescale that
		// perturbed their order would break the re-baseline invariant for a reason
		// unrelated to the new stage.
		double popularity = rng.nextDouble() * 500 / 16;

		// ~10% of the universe lands inside the NEW_RELEASES 60-day window at
		// BASE_DAY so that shelf has stock; the rest spreads over three decades
		LocalDate releaseDate = rng.nextInt(10) == 0
			? BASE_DAY.minusDays(5 + rng.nextInt(50))
			: BASE_DAY.minusDays(90 + rng.nextInt(365 * 30));

		List<Integer> providers = new ArrayList<>();
		for (Integer p : PROVIDER_POOL) {
			if (rng.nextInt(4) == 0) providers.add(p);
		}

		String name = (rng.nextBoolean() ? "The " : "")
			+ NAME_ADJECTIVES[rng.nextInt(NAME_ADJECTIVES.length)]
			+ " " + NAME_NOUNS[rng.nextInt(NAME_NOUNS.length)];

		return new SynTitle("syn-" + (101 + i), type, name, releaseDate,
			List.copyOf(genres), List.copyOf(keywords),
			castIds.stream().map(this::person).toList(),
			List.of(person(directorId)),
			voteCount, voteAverage, popularity, List.copyOf(providers));
	}

	private CachedPerson person(int id) {
		return new CachedPerson(id, PEOPLE_NAMES[id - 900]);
	}

	SynTitle titleFor(String tmdbId) {
		return byId.get(tmdbId);
	}

	String keywordName(int id) {
		return keywordNamesById.getOrDefault(id, "keyword-" + id);
	}

	String personName(int id) {
		return id >= 900 && id - 900 < PEOPLE_NAMES.length ? PEOPLE_NAMES[id - 900] : "Person " + id;
	}

	/**
	 * Synthetic tmdb_title_cache row for a universe title; null for unknown ids.
	 *
	 * <p>{@code cacheKey} is the medium-scoped cache key (#394) production code now looks up
	 * with — {@code FixtureWorld} passes through whatever {@code TmdbTitleCacheRepository
	 * .findAllById} was asked for. Every synthetic id already belongs to exactly one type for
	 * the life of the universe (fixed at generation, alternating by index), so stripping the
	 * prefix to find the title and re-tagging the row with the key as given is safe.
	 */
	TmdbTitleCache cacheRowFor(String cacheKey) {
		SynTitle t = byId.get(TmdbCacheKey.tmdbIdOf(cacheKey));
		if (t == null) return null;
		TmdbTitleCache row = new TmdbTitleCache();
		row.setTmdbId(cacheKey);
		row.setType(t.type() == TitleType.MOVIE ? "MOVIE" : "TV");
		row.setName(t.name());
		row.setGenreIds(t.genreIds());
		row.setKeywordIds(t.keywordIds());
		row.setKeywords(t.keywordIds().stream()
			.map(id -> new CachedKeyword(id, keywordNamesById.get(id)))
			.toList());
		row.setVoteCount(t.voteCount());
		row.setTopCast(t.topCast());
		row.setDirectors(t.directors());
		row.setWatchProviders(Map.of("US", t.usProviderIds()));
		return row;
	}

	// ── Feed synthesis ────────────────────────────────────────

	List<TitleSearchResponse> recommendations(TitleType type, String seedTmdbId, List<Integer> seedGenres,
			List<Integer> seedKeywords, int page) {
		return seedFeed(type, seedTmdbId, seedGenres, seedKeywords, page, 31);
	}

	List<TitleSearchResponse> similar(TitleType type, String seedTmdbId, List<Integer> seedGenres,
			List<Integer> seedKeywords, int page) {
		return seedFeed(type, seedTmdbId, seedGenres, seedKeywords, page, 67);
	}

	// Rank the universe by affinity to the seed (shared genres dominate, shared
	// keywords refine, a seed-specific hash breaks ties) and serve the top 60 —
	// three pages, like a typical real recommendations feed before it runs dry
	private List<TitleSearchResponse> seedFeed(TitleType type, String seedTmdbId, List<Integer> seedGenres,
			List<Integer> seedKeywords, int page, int salt) {
		List<SynTitle> pool = universe.stream()
			.filter(t -> t.type() == type && !t.tmdbId().equals(seedTmdbId))
			.filter(t -> seedGenres.isEmpty() || t.genreIds().stream().anyMatch(seedGenres::contains))
			.sorted(Comparator
				.comparingLong((SynTitle t) -> affinity(t, seedGenres, seedKeywords, seedTmdbId, salt))
				.reversed())
			.limit(60)
			.toList();
		return page(pool, page);
	}

	private long affinity(SynTitle t, List<Integer> seedGenres, List<Integer> seedKeywords,
			String seedTmdbId, int salt) {
		long sharedGenres = t.genreIds().stream().filter(seedGenres::contains).count();
		long sharedKeywords = seedKeywords == null ? 0
			: t.keywordIds().stream().filter(seedKeywords::contains).count();
		// String.hashCode is specified and stable across JVMs, keeping runs
		// byte-identical everywhere
		int tiebreak = (seedTmdbId + "|" + t.tmdbId() + "|" + salt).hashCode() & 0xffff;
		return sharedGenres * 1_000_000L + sharedKeywords * 100_000L + tiebreak;
	}

	List<TitleSearchResponse> trending(TitleType type, int page) {
		List<SynTitle> pool = universe.stream()
			.filter(t -> t.type() == type)
			.sorted(Comparator.comparingDouble(SynTitle::popularity).reversed())
			.limit(60)
			.toList();
		return page(pool, page);
	}

	List<TitleSearchResponse> discover(TitleType type, List<Integer> genreIds, List<Integer> keywordIds,
			int voteCountGte, String sortBy, LocalDate releasedAfter, LocalDate releasedBefore,
			String watchRegion, List<Integer> providerIds, int page) {
		Predicate<SynTitle> matches = t -> t.type() == type
			&& t.voteCount() >= voteCountGte
			&& (genreIds.isEmpty() || t.genreIds().stream().anyMatch(genreIds::contains))
			&& (keywordIds.isEmpty() || t.keywordIds().stream().anyMatch(keywordIds::contains))
			&& (releasedAfter == null || !t.releaseDate().isBefore(releasedAfter))
			&& (releasedBefore == null || !t.releaseDate().isAfter(releasedBefore))
			&& providerMatch(t, watchRegion, providerIds);
		Comparator<SynTitle> order = "vote_average.desc".equals(sortBy)
			? Comparator.comparingDouble(SynTitle::voteAverage).reversed()
			: Comparator.comparingDouble(SynTitle::popularity).reversed();
		return page(universe.stream().filter(matches).sorted(order).toList(), page);
	}

	private boolean providerMatch(SynTitle t, String watchRegion, List<Integer> providerIds) {
		if (watchRegion == null || providerIds == null || providerIds.isEmpty()) return true;
		// The universe only carries US provider data, mirroring the fixtures
		if (!"US".equals(watchRegion)) return false;
		return t.usProviderIds().stream().anyMatch(providerIds::contains);
	}

	List<TitleSearchResponse> discoverByPerson(int personId, int voteCountGte, int page) {
		List<SynTitle> pool = universe.stream()
			.filter(t -> t.type() == TitleType.MOVIE && t.voteCount() >= voteCountGte)
			.filter(t -> t.topCast().stream().anyMatch(p -> p.id() == personId)
				|| t.directors().stream().anyMatch(p -> p.id() == personId))
			.sorted(Comparator.comparingDouble(SynTitle::popularity).reversed())
			.toList();
		return page(pool, page);
	}

	List<TitleSearchResponse> discoverByKeyword(TitleType type, int keywordId, int voteCountGte,
			String watchRegion, List<Integer> providerIds, int page) {
		List<SynTitle> pool = universe.stream()
			.filter(t -> t.type() == type && t.voteCount() >= voteCountGte
				&& t.keywordIds().contains(keywordId)
				&& providerMatch(t, watchRegion, providerIds))
			.sorted(Comparator.comparingDouble(SynTitle::popularity).reversed())
			.toList();
		return page(pool, page);
	}

	/**
	 * Collection parts for a fixture-owned franchise: the owned parts (passed in
	 * by the fixture world, as TMDB returns the whole collection including what
	 * you already have) plus deterministic synthetic sequels — two released, one
	 * future-dated, and one with no date, exercising the #272 unreleased/undated
	 * exclusion rules.
	 */
	List<TitleSearchResponse> collectionParts(int collectionId, String collectionName,
			List<TitleSearchResponse> ownedParts) {
		String base = collectionName != null
			? collectionName.replace(" Collection", "")
			: "Collection " + collectionId;
		List<TitleSearchResponse> parts = new ArrayList<>(ownedParts);
		parts.add(part(collectionId, 2, base + " Part Two", BASE_DAY.minusDays(400)));
		parts.add(part(collectionId, 3, base + " Part Three", BASE_DAY.minusDays(60)));
		parts.add(part(collectionId, 4, base + " Part Four", BASE_DAY.plusDays(300)));
		parts.add(part(collectionId, 5, base + ": Origins", null));
		return parts;
	}

	private TitleSearchResponse part(int collectionId, int index, String name, LocalDate releaseDate) {
		return new TitleSearchResponse("syn-col" + collectionId + "-" + index, "tmdb",
			TitleType.MOVIE, name, null, releaseDate, null, List.of(878, 12));
	}

	private List<TitleSearchResponse> page(List<SynTitle> pool, int page) {
		int from = (page - 1) * PAGE_SIZE;
		if (from >= pool.size()) return List.of();
		return pool.subList(from, Math.min(from + PAGE_SIZE, pool.size())).stream()
			.map(this::toResponse)
			.toList();
	}

	private TitleSearchResponse toResponse(SynTitle t) {
		// Carries popularity (#374) so a future obscurity gate can actually see it
		// in the harness — a null here would make every synthetic candidate fail
		// such a gate and read as a broken stage rather than an unwired fixture.
		return new TitleSearchResponse(t.tmdbId(), "tmdb", t.type(), t.name(),
			null, t.releaseDate(), null, t.genreIds(), null, t.popularity());
	}
}
