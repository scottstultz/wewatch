package com.wewatch.api.tuning;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.springframework.web.client.RestClient;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.TmdbTitleCache;
import com.wewatch.api.tmdb.TmdbClient;

/**
 * A {@link TmdbClient} that never touches the network (#288): every feed the
 * suggestion pipeline calls is answered from the deterministic
 * {@link SyntheticCatalog}, with an optional overlay of recorded real-TMDB
 * responses ({@code recordedByKey}) taking precedence when present. Subclasses
 * the real client and overrides only the seven feed methods the pipeline uses;
 * the superclass RestClient is built but never invoked.
 */
final class HarnessTmdbClient extends TmdbClient {

	private final SyntheticCatalog catalog;
	// Cache-row lookup for any owned seed, so recommendations/similar can rank
	// the synthetic universe by the seed's own genres and keywords — the same
	// coherence the real feeds have
	private final Function<String, TmdbTitleCache> cacheLookup;
	private final IntFunction<CollectionData> collectionLookup;
	// Recorded real-TMDB responses keyed by request signature (see README);
	// empty by default, so the default harness run is pure synthetic
	private final Map<String, List<TitleSearchResponse>> recordedByKey;

	record CollectionData(String name, List<TitleSearchResponse> ownedParts) {}

	HarnessTmdbClient(
		SyntheticCatalog catalog,
		Function<String, TmdbTitleCache> cacheLookup,
		IntFunction<CollectionData> collectionLookup,
		Map<String, List<TitleSearchResponse>> recordedByKey
	) {
		super(RestClient.builder(), "harness-offline-no-network");
		this.catalog = catalog;
		this.cacheLookup = cacheLookup;
		this.collectionLookup = collectionLookup;
		this.recordedByKey = recordedByKey;
	}

	@Override
	public List<TitleSearchResponse> getRecommendations(TitleType type, String tmdbId, int page) {
		List<TitleSearchResponse> recorded = recordedByKey.get("recommendations:" + type + ":" + tmdbId + ":" + page);
		if (recorded != null) return recorded;
		TmdbTitleCache seed = cacheLookup.apply(tmdbId);
		return catalog.recommendations(type, tmdbId, genresOf(seed), keywordsOf(seed), page);
	}

	@Override
	public List<TitleSearchResponse> getSimilar(TitleType type, String tmdbId, int page) {
		List<TitleSearchResponse> recorded = recordedByKey.get("similar:" + type + ":" + tmdbId + ":" + page);
		if (recorded != null) return recorded;
		TmdbTitleCache seed = cacheLookup.apply(tmdbId);
		return catalog.similar(type, tmdbId, genresOf(seed), keywordsOf(seed), page);
	}

	@Override
	public List<TitleSearchResponse> getTrending(TitleType type, int page) {
		List<TitleSearchResponse> recorded = recordedByKey.get("trending:" + type + ":" + page);
		if (recorded != null) return recorded;
		return catalog.trending(type, page);
	}

	@Override
	public List<TitleSearchResponse> discover(TitleType type, List<Integer> genreIds, List<Integer> keywordIds,
			int voteCountGte, String sortBy, java.time.LocalDate releasedAfter, java.time.LocalDate releasedBefore,
			String watchRegion, List<Integer> watchProviderIds, int page) {
		return catalog.discover(type, genreIds, keywordIds, voteCountGte, sortBy,
			releasedAfter, releasedBefore, watchRegion, watchProviderIds, page);
	}

	@Override
	public List<TitleSearchResponse> discoverByPerson(int personId, int voteCountGte, int page) {
		return catalog.discoverByPerson(personId, voteCountGte, page);
	}

	@Override
	public List<TitleSearchResponse> discoverByKeyword(TitleType type, int keywordId, int voteCountGte,
			String watchRegion, List<Integer> watchProviderIds, int page) {
		return catalog.discoverByKeyword(type, keywordId, voteCountGte, watchRegion, watchProviderIds, page);
	}

	@Override
	public List<TitleSearchResponse> getCollectionParts(int collectionId) {
		CollectionData data = collectionLookup.apply(collectionId);
		if (data == null) return List.of();
		return catalog.collectionParts(collectionId, data.name(), data.ownedParts());
	}

	private static List<Integer> genresOf(TmdbTitleCache seed) {
		return seed != null && seed.getGenreIds() != null ? seed.getGenreIds() : List.of();
	}

	private static List<Integer> keywordsOf(TmdbTitleCache seed) {
		return seed != null && seed.getKeywordIds() != null ? seed.getKeywordIds() : List.of();
	}
}
