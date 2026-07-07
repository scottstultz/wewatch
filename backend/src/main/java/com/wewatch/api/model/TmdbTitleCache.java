package com.wewatch.api.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tmdb_title_cache")
public class TmdbTitleCache {

	@Id
	@Column(name = "tmdb_id", length = 255)
	private String tmdbId;

	@Column(name = "type", nullable = false, length = 32)
	private String type;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "overview", columnDefinition = "TEXT")
	private String overview;

	@Column(name = "poster_path", length = 255)
	private String posterPath;

	@Column(name = "status", length = 100)
	private String status;

	@Column(name = "first_air_date")
	private LocalDate firstAirDate;

	@Column(name = "number_of_seasons")
	private Integer numberOfSeasons;

	@Convert(converter = IntegerListConverter.class)
	@Column(name = "genre_ids", length = 255)
	private List<Integer> genreIds;

	@Convert(converter = IntegerListConverter.class)
	@Column(name = "keyword_ids", length = 500)
	private List<Integer> keywordIds;

	// Same ids with display names (#271): keyword-seeded shelf labels need the
	// name; scoring keeps reading the flat keyword_ids column. null means "not
	// fetched since #271" — TTL refresh backfills.
	@Convert(converter = CachedKeywordListConverter.class)
	@Column(name = "keywords", columnDefinition = "TEXT")
	private List<CachedKeyword> keywords;

	@Column(name = "vote_count")
	private Integer voteCount;

	@Convert(converter = CachedPersonListConverter.class)
	@Column(name = "top_cast", columnDefinition = "TEXT")
	private List<CachedPerson> topCast;

	@Convert(converter = CachedPersonListConverter.class)
	@Column(name = "directors", columnDefinition = "TEXT")
	private List<CachedPerson> directors;

	// Flatrate (subscription) provider ids per watch region (#270). null means
	// "not fetched yet" (pre-#270 row); an empty map means "fetched, streamable
	// nowhere". TTL refresh keeps it current as availability shifts.
	@Convert(converter = RegionProviderIdsConverter.class)
	@Column(name = "watch_providers", columnDefinition = "TEXT")
	private Map<String, List<Integer>> watchProviders;

	// TMDB collection a movie belongs to (#272), for the "Next in the series"
	// shelf. Movies only; TV rows leave both null.
	@Column(name = "collection_id")
	private Integer collectionId;

	@Column(name = "collection_name", length = 255)
	private String collectionName;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	public TmdbTitleCache() {
	}

	public String getTmdbId() { return tmdbId; }
	public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }

	public String getType() { return type; }
	public void setType(String type) { this.type = type; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getOverview() { return overview; }
	public void setOverview(String overview) { this.overview = overview; }

	public String getPosterPath() { return posterPath; }
	public void setPosterPath(String posterPath) { this.posterPath = posterPath; }

	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }

	public LocalDate getFirstAirDate() { return firstAirDate; }
	public void setFirstAirDate(LocalDate firstAirDate) { this.firstAirDate = firstAirDate; }

	public Integer getNumberOfSeasons() { return numberOfSeasons; }
	public void setNumberOfSeasons(Integer numberOfSeasons) { this.numberOfSeasons = numberOfSeasons; }

	public List<Integer> getGenreIds() { return genreIds; }
	public void setGenreIds(List<Integer> genreIds) { this.genreIds = genreIds; }

	public List<Integer> getKeywordIds() { return keywordIds; }
	public void setKeywordIds(List<Integer> keywordIds) { this.keywordIds = keywordIds; }

	public List<CachedKeyword> getKeywords() { return keywords; }
	public void setKeywords(List<CachedKeyword> keywords) { this.keywords = keywords; }

	public Integer getVoteCount() { return voteCount; }
	public void setVoteCount(Integer voteCount) { this.voteCount = voteCount; }

	public List<CachedPerson> getTopCast() { return topCast; }
	public void setTopCast(List<CachedPerson> topCast) { this.topCast = topCast; }

	public List<CachedPerson> getDirectors() { return directors; }
	public void setDirectors(List<CachedPerson> directors) { this.directors = directors; }

	public Map<String, List<Integer>> getWatchProviders() { return watchProviders; }
	public void setWatchProviders(Map<String, List<Integer>> watchProviders) { this.watchProviders = watchProviders; }

	public Integer getCollectionId() { return collectionId; }
	public void setCollectionId(Integer collectionId) { this.collectionId = collectionId; }

	public String getCollectionName() { return collectionName; }
	public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

	public Instant getFetchedAt() { return fetchedAt; }
	public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
