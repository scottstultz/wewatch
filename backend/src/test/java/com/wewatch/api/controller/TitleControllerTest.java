package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.dto.PersonSearchResult;
import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.dto.TitleSearchResults;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.RecommendationService;
import com.wewatch.api.service.TitleRatingService;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.TmdbCacheService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.tmdb.TmdbCastMember;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbCredits;
import com.wewatch.api.tmdb.TmdbGenre;
import com.wewatch.api.tmdb.TmdbMovieDetail;
import com.wewatch.api.tmdb.TmdbRegionWatchProviders;
import com.wewatch.api.tmdb.TmdbTvDetail;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;
import com.wewatch.api.tmdb.TmdbWatchProvider;
import com.wewatch.api.tmdb.TmdbVideo;
import com.wewatch.api.tmdb.TmdbVideos;
import com.wewatch.api.tmdb.TmdbWatchProviders;

@WebMvcTest(TitleController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class TitleControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private TitleService titleService;

	@MockBean
	private TmdbClient tmdbClient;

	@MockBean
	private TmdbCacheService tmdbCacheService;

	@MockBean
	private TitleRatingService titleRatingService;

	@MockBean
	private RecommendationService recommendationService;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	private static final User TEST_USER = new User(1L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");

	private static final Jwt TEST_JWT = Jwt.withTokenValue("test-token")
		.header("alg", "HS256")
		.claim("sub", "1")
		.issuer("wewatch")
		.issuedAt(Instant.EPOCH)
		.expiresAt(Instant.EPOCH.plusSeconds(86400))
		.build();

	@BeforeEach
	void setupAuth() {
		when(jwtDecoder.decode(any())).thenReturn(TEST_JWT);
		when(userService.findById(1L)).thenReturn(TEST_USER);
	}

	@Test
	void resolveTitleReturnsExistingTitleWithoutContactingTmdb() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existingTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			"A computer hacker learns about the true nature of reality.",
			LocalDate.parse("1999-03-31"),
			"https://image.tmdb.org/t/p/w500/matrix.jpg",
			createdAt,
			createdAt
		);

		when(titleService.findOrCreate(eq("TMDB"), eq("603"), any())).thenReturn(existingTitle);

		mockMvc.perform(
			post("/api/titles/resolve")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.type").value("MOVIE"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.overview").value("A computer hacker learns about the true nature of reality."))
			.andExpect(jsonPath("$.releaseDate").value("1999-03-31"))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/matrix.jpg"))
			.andExpect(jsonPath("$.createdAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-28T12:00:00Z"));

		verify(titleService).findOrCreate(eq("TMDB"), eq("603"), any());
		verifyNoInteractions(tmdbClient);
	}

	@Test
	@SuppressWarnings("unchecked")
	void resolveTitleBuildsMovieFromTmdbDetailIgnoringClientMetadata() throws Exception {
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", "A computer hacker learns the truth.", "/matrix.jpg",
			"Released", "1999-03-31",
			List.of(new TmdbGenre(28, "Action")),
			8.2, 25000, null, null
		, null, 136, null));
		when(titleService.findOrCreate(eq("TMDB"), eq("603"), any())).thenAnswer(invocation -> {
			Title candidate = ((Supplier<Title>) invocation.getArgument(2)).get();
			candidate.setId(1L);
			return candidate;
		});

		mockMvc.perform(
			post("/api/titles/resolve")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE",
					  "name": "junk from the client",
					  "overview": "junk overview"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.type").value("MOVIE"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.overview").value("A computer hacker learns the truth."))
			.andExpect(jsonPath("$.releaseDate").value("1999-03-31"))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/matrix.jpg"));

		verify(tmdbClient).getMovieDetail("603");
	}

	@Test
	@SuppressWarnings("unchecked")
	void resolveTitleBuildsTvShowFromTmdbDetail() throws Exception {
		when(tmdbClient.getTvDetail("1399")).thenReturn(new TmdbTvDetail(
			1399, 2, "Ended", "2011-04-17",
			List.of(),
			"Game of Thrones", "Nine noble families fight for control.", "/got.jpg",
			List.of(new TmdbGenre(10765, "Sci-Fi & Fantasy")),
			8.4, 21000, null, null
		, null));
		when(titleService.findOrCreate(eq("TMDB"), eq("1399"), any())).thenAnswer(invocation -> {
			Title candidate = ((Supplier<Title>) invocation.getArgument(2)).get();
			candidate.setId(5L);
			return candidate;
		});

		mockMvc.perform(
			post("/api/titles/resolve")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "1399",
					  "externalSource": "TMDB",
					  "type": "TV"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(5))
			.andExpect(jsonPath("$.externalId").value("1399"))
			.andExpect(jsonPath("$.type").value("TV"))
			.andExpect(jsonPath("$.name").value("Game of Thrones"))
			.andExpect(jsonPath("$.releaseDate").value("2011-04-17"))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/got.jpg"));

		verify(tmdbClient).getTvDetail("1399");
	}

	@Test
	void resolveTitleReturnsBadRequestForInvalidPayload() throws Exception {
		mockMvc.perform(
			post("/api/titles/resolve")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "",
					  "externalSource": "",
					  "type": null
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void resolveTitleReturnsBadRequestForUnsupportedSource() throws Exception {
		mockMvc.perform(
			post("/api/titles/resolve")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "tt0133093",
					  "externalSource": "IMDB",
					  "type": "MOVIE"
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.message").value("Unsupported external source: IMDB"));

		verifyNoInteractions(titleService, tmdbClient);
	}

	@Test
	void resolveTitleReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(
			post("/api/titles/resolve")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE"
					}
					""")
		)
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rawCreateTitleEndpointIsGone() throws Exception {
		mockMvc.perform(
			post("/api/titles")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE",
					  "name": "junk"
					}
					""")
		)
			.andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(titleService);
	}

	@Test
	void getTitleReturnsPersistedTitle() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existingTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			createdAt,
			createdAt
		);

		when(titleService.findById(1L)).thenReturn(existingTitle);

		mockMvc.perform(get("/api/titles/1")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.type").value("MOVIE"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.releaseDate").value("1999-03-31"));
	}

	@Test
	void updateTitleEndpointIsGone() throws Exception {
		mockMvc.perform(
			patch("/api/titles/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "junk"
					}
					""")
		)
			.andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(titleService);
	}

	@Test
	void getTitlesReturnsMatchesForExternalIdentifiers() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existingTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			createdAt,
			createdAt
		);

		when(titleService.findByFilters(eq("603"), eq("TMDB"), isNull(), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(existingTitle)));

		mockMvc.perform(
			get("/api/titles")
				.header("Authorization", "Bearer test-token")
				.param("externalId", "603")
				.param("externalSource", "TMDB")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].externalId").value("603"))
			.andExpect(jsonPath("$.content[0].externalSource").value("TMDB"))
			.andExpect(jsonPath("$.content[0].type").value("MOVIE"))
			.andExpect(jsonPath("$.content[0].name").value("The Matrix"));

		verify(titleService).findByFilters(eq("603"), eq("TMDB"), isNull(), isNull(), any(Pageable.class));
	}

	@Test
	void getTitlesReturnsMatchesForName() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existingTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			createdAt,
			createdAt
		);

		when(titleService.findByFilters(isNull(), isNull(), isNull(), eq("The Matrix"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(existingTitle)));

		mockMvc.perform(get("/api/titles")
			.header("Authorization", "Bearer test-token")
			.param("name", "The Matrix"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].name").value("The Matrix"));

		verify(titleService).findByFilters(isNull(), isNull(), isNull(), eq("The Matrix"), any(Pageable.class));
	}

	@Test
	void getTitlesCombinesQueryParameters() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title existingTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			createdAt,
			createdAt
		);

		when(titleService.findByFilters(eq("603"), eq("TMDB"), eq(TitleType.MOVIE), eq("The Matrix"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(existingTitle)));

		mockMvc.perform(
			get("/api/titles")
				.header("Authorization", "Bearer test-token")
				.param("externalId", "603")
				.param("externalSource", "TMDB")
				.param("type", "MOVIE")
				.param("name", "The Matrix")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].type").value("MOVIE"))
			.andExpect(jsonPath("$.content[0].name").value("The Matrix"));

		verify(titleService).findByFilters(eq("603"), eq("TMDB"), eq(TitleType.MOVIE), eq("The Matrix"), any(Pageable.class));
	}

	@Test
	void getTitlesReturnsEmptyListWhenNoTitlesMatch() throws Exception {
		when(titleService.findByFilters(isNull(), isNull(), isNull(), eq("Missing Title"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

		mockMvc.perform(get("/api/titles")
			.header("Authorization", "Bearer test-token")
			.param("name", "Missing Title"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getTitleReturnsNotFoundWhenMissing() throws Exception {
		when(titleService.findById(42L)).thenThrow(new NoSuchElementException("Title not found: 42"));

		mockMvc.perform(get("/api/titles/42")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Title not found: 42"));
	}

	@Test
	void getTitleReturnsUnauthorizedWhenNoToken() throws Exception {
		mockMvc.perform(get("/api/titles/1"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void searchTitlesReturnsResults() throws Exception {
		List<TitleSearchResponse> searchResults = List.of(
			new TitleSearchResponse(
				"27205",
				"TMDB",
				TitleType.MOVIE,
				"Inception",
				"A thief who steals corporate secrets.",
				LocalDate.parse("2010-07-16"),
				"https://image.tmdb.org/t/p/w500/poster.jpg",
				null,
				null,
				87.5
			)
		);

		List<PersonSearchResult> people = List.of(
			new PersonSearchResult(12345, "Leonardo DiCaprio", "https://image.tmdb.org/t/p/w185/leo.jpg")
		);

		when(tmdbClient.search("inception", null))
			.thenReturn(new TitleSearchResults(searchResults, people));

		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token")
			.param("q", "inception"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.titles[0].externalId").value("27205"))
			.andExpect(jsonPath("$.titles[0].externalSource").value("TMDB"))
			.andExpect(jsonPath("$.titles[0].type").value("MOVIE"))
			.andExpect(jsonPath("$.titles[0].name").value("Inception"))
			.andExpect(jsonPath("$.titles[0].releaseDate").value("2010-07-16"))
			.andExpect(jsonPath("$.titles[0].posterUrl").value("https://image.tmdb.org/t/p/w500/poster.jpg"))
			// popularity is a pipeline-internal signal and must never reach a client (#374).
			// The fixture above carries 87.5, so this fails the moment @JsonIgnore comes off.
			.andExpect(jsonPath("$.titles[0].popularity").doesNotExist())
			.andExpect(jsonPath("$.people[0].id").value(12345))
			.andExpect(jsonPath("$.people[0].name").value("Leonardo DiCaprio"))
			.andExpect(jsonPath("$.people[0].profileUrl").value("https://image.tmdb.org/t/p/w185/leo.jpg"));

		verify(tmdbClient).search("inception", null);
	}

	@Test
	void searchTitlesReturnsEmptyListWhenNoResultsFound() throws Exception {
		when(tmdbClient.search("xyznotafilm", null))
			.thenReturn(new TitleSearchResults(List.of(), List.of()));

		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token")
			.param("q", "xyznotafilm"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.titles").isArray())
			.andExpect(jsonPath("$.titles").isEmpty())
			.andExpect(jsonPath("$.people").isArray())
			.andExpect(jsonPath("$.people").isEmpty());
	}

	@Test
	void searchTitlesReturnsBadGatewayOnTmdbError() throws Exception {
		when(tmdbClient.search(any(), any()))
			.thenThrow(new TmdbApiException("TMDB search failed", new RuntimeException()));

		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token")
			.param("q", "inception"))
			.andExpect(status().isBadGateway())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(502))
			.andExpect(jsonPath("$.message").value("TMDB search failed"));
	}

	@Test
	void searchTitlesMissingQueryReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest());
	}

	// ─── GET /api/titles/recommendations (#358) ───────────────────────────────

	@Test
	void recommendationsReturnsRelatedTitles() throws Exception {
		when(recommendationService.recommendationsFor(TitleType.MOVIE, "603")).thenReturn(List.of(
			new TitleSearchResponse(
				"604",
				"TMDB",
				TitleType.MOVIE,
				"The Matrix Reloaded",
				"Neo and the rebel leaders.",
				LocalDate.parse("2003-05-15"),
				"https://image.tmdb.org/t/p/w500/reloaded.jpg",
				null
			)
		));

		mockMvc.perform(get("/api/titles/recommendations")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].externalId").value("604"))
			.andExpect(jsonPath("$[0].type").value("MOVIE"))
			.andExpect(jsonPath("$[0].name").value("The Matrix Reloaded"));

		verify(recommendationService).recommendationsFor(TitleType.MOVIE, "603");
	}

	@Test
	void recommendationsReturnsBadGatewayOnTmdbError() throws Exception {
		when(recommendationService.recommendationsFor(any(), any()))
			.thenThrow(new TmdbApiException("TMDB recommendations failed", new RuntimeException()));

		mockMvc.perform(get("/api/titles/recommendations")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("type", "MOVIE"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.status").value(502))
			.andExpect(jsonPath("$.message").value("TMDB recommendations failed"));
	}

	// ─── GET /api/titles/{titleId}/seasons ────────────────────────────────────

	private static final Title TV_TITLE = new Title(
		5L, "1399", "TMDB", TitleType.TV, "Game of Thrones",
		null, null, null, Instant.EPOCH, Instant.EPOCH
	);

	private static final Title MOVIE_TITLE = new Title(
		6L, "603", "TMDB", TitleType.MOVIE, "The Matrix",
		null, null, null, Instant.EPOCH, Instant.EPOCH
	);

	@Test
	void getSeasonsReturnsSummariesForTvTitle() throws Exception {
		when(titleService.findById(5L)).thenReturn(TV_TITLE);
		when(tmdbCacheService.getSeasons("1399")).thenReturn(List.of(
			new TmdbTvSeason(3625, 1, "Season 1", null, "/s1.jpg", 10, "2011-04-17", null),
			new TmdbTvSeason(3626, 2, "Season 2", null, null, 10, "2012-04-01", null)
		));

		mockMvc.perform(get("/api/titles/5/seasons")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].seasonNumber").value(1))
			.andExpect(jsonPath("$[0].name").value("Season 1"))
			.andExpect(jsonPath("$[0].episodeCount").value(10))
			.andExpect(jsonPath("$[0].posterUrl").value("https://image.tmdb.org/t/p/w500/s1.jpg"))
			.andExpect(jsonPath("$[0].airDate").value("2011-04-17"))
			.andExpect(jsonPath("$[1].seasonNumber").value(2))
			.andExpect(jsonPath("$[1].posterUrl").doesNotExist());
	}

	@Test
	void getSeasonsReturnsBadRequestForMovie() throws Exception {
		when(titleService.findById(6L)).thenReturn(MOVIE_TITLE);

		mockMvc.perform(get("/api/titles/6/seasons")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Season data is only available for TV shows"));
	}

	// ─── GET /api/titles/{titleId}/seasons/{seasonNumber} ────────────────────

	@Test
	void getSeasonDetailReturnsEpisodesForTvTitle() throws Exception {
		when(titleService.findById(5L)).thenReturn(TV_TITLE);
		when(tmdbCacheService.getSeasonDetail("1399", 1)).thenReturn(new TmdbTvSeason(
			3625, 1, "Season 1", "The first season.", "/s1.jpg", 10, "2011-04-17",
			List.of(
				new TmdbTvEpisode(63056, 1, "Winter Is Coming", "Jon Arryn has died.", "2011-04-17", "/ep1.jpg", 62),
				new TmdbTvEpisode(63057, 2, "The Kingsroad", "Bitter truths.", "2011-04-24", null, 56)
			)
		));

		mockMvc.perform(get("/api/titles/5/seasons/1")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seasonNumber").value(1))
			.andExpect(jsonPath("$.name").value("Season 1"))
			.andExpect(jsonPath("$.overview").value("The first season."))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/s1.jpg"))
			.andExpect(jsonPath("$.episodes.length()").value(2))
			.andExpect(jsonPath("$.episodes[0].episodeNumber").value(1))
			.andExpect(jsonPath("$.episodes[0].name").value("Winter Is Coming"))
			.andExpect(jsonPath("$.episodes[0].airDate").value("2011-04-17"))
			.andExpect(jsonPath("$.episodes[0].stillUrl").value("https://image.tmdb.org/t/p/w300/ep1.jpg"))
			.andExpect(jsonPath("$.episodes[0].runtimeMinutes").value(62))
			.andExpect(jsonPath("$.episodes[1].stillUrl").doesNotExist());
	}

	@Test
	void getSeasonDetailReturnsBadRequestForMovie() throws Exception {
		when(titleService.findById(6L)).thenReturn(MOVIE_TITLE);

		mockMvc.perform(get("/api/titles/6/seasons/1")
			.header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Season data is only available for TV shows"));
	}

	// ─── GET /api/titles/detail ───────────────────────────────────────────────

	@Test
	void getTitleDetailReturnsMovieDetail() throws Exception {
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", "A computer hacker learns the truth.", "/matrix.jpg",
			"Released", "1999-03-31",
			List.of(new TmdbGenre(28, "Action"), new TmdbGenre(878, "Science Fiction")),
			8.2, 25000, null, null
		, null, 136, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.type").value("MOVIE"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.overview").value("A computer hacker learns the truth."))
			.andExpect(jsonPath("$.releaseDate").value("1999-03-31"))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/matrix.jpg"))
			.andExpect(jsonPath("$.status").value("Released"))
			.andExpect(jsonPath("$.genres.length()").value(2))
			.andExpect(jsonPath("$.genres[0]").value("Action"))
			.andExpect(jsonPath("$.voteAverage").value(8.2))
			.andExpect(jsonPath("$.voteCount").value(25000))
			.andExpect(jsonPath("$.runtimeMinutes").value(136))
			.andExpect(jsonPath("$.seasonCount").doesNotExist())
			.andExpect(jsonPath("$.seasons").doesNotExist())
			// No credits block from TMDB — an empty cast, not a null (#295)
			.andExpect(jsonPath("$.cast.length()").value(0))
			// No videos block from TMDB — no link is rendered (#340)
			.andExpect(jsonPath("$.trailerUrl").doesNotExist());

		verify(tmdbClient).getMovieDetail("603");
	}

	@Test
	void getTitleDetailReturnsTheTrailerUrlForAMovie() throws Exception {
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", null, null, "Released", "1999-03-31",
			List.of(), 8.2, 25000, null, null, null, 136,
			new TmdbVideos(List.of(
				new TmdbVideo("teaser1", "YouTube", "Teaser", true, "2024-01-01T00:00:00.000Z"),
				new TmdbVideo("m8e-FF8MsqU", "YouTube", "Trailer", true, "2020-01-01T00:00:00.000Z")))));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trailerUrl").value("https://www.youtube.com/watch?v=m8e-FF8MsqU"));

		// Videos ride the existing detail call — no extra TMDB request
		verify(tmdbClient).getMovieDetail("603");
		verifyNoMoreInteractions(tmdbClient);
	}

	@Test
	void getTitleDetailReturnsTheTrailerUrlForATvShow() throws Exception {
		when(tmdbClient.getTvDetail("1399")).thenReturn(new TmdbTvDetail(
			1399, 8, "Ended", "2011-04-17", List.of(),
			"Game of Thrones", null, null, List.of(), 8.4, 21000, null, null,
			new TmdbVideos(List.of(
				new TmdbVideo("BpJYNVhGf1s", "YouTube", "Trailer", true, "2011-04-01T00:00:00.000Z")))));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "1399")
			.param("externalSource", "TMDB")
			.param("type", "TV"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trailerUrl").value("https://www.youtube.com/watch?v=BpJYNVhGf1s"));
	}

	@Test
	void getTitleDetailReturnsCastInBillingOrderWithProfileUrls() throws Exception {
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", null, null, "Released", "1999-03-31",
			List.of(), 8.2, 25000,
			new TmdbCredits(List.of(
				new TmdbCastMember(2L, "Laurence Fishburne", "Morpheus", "/morpheus.jpg", 1),
				new TmdbCastMember(1L, "Keanu Reeves", "Neo", "/neo.jpg", 0),
				// TMDB has no headshot for this one — the client renders a placeholder
				new TmdbCastMember(3L, "Joe Pantoliano", "Cypher", null, 2)),
				List.of()),
			null, null, 136, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cast.length()").value(3))
			.andExpect(jsonPath("$.cast[0].id").value(1))
			.andExpect(jsonPath("$.cast[0].name").value("Keanu Reeves"))
			.andExpect(jsonPath("$.cast[0].character").value("Neo"))
			.andExpect(jsonPath("$.cast[0].profileUrl").value("https://image.tmdb.org/t/p/w185/neo.jpg"))
			.andExpect(jsonPath("$.cast[1].name").value("Laurence Fishburne"))
			.andExpect(jsonPath("$.cast[2].name").value("Joe Pantoliano"))
			.andExpect(jsonPath("$.cast[2].profileUrl").doesNotExist());

		// Credits ride the existing detail call — no extra TMDB request
		verify(tmdbClient).getMovieDetail("603");
		verifyNoMoreInteractions(tmdbClient);
	}

	@Test
	void getTitleDetailCapsCastAtTenMembers() throws Exception {
		List<TmdbCastMember> fifteen = IntStream.range(0, 15)
			.mapToObj(i -> new TmdbCastMember(i, "Actor " + i, "Role " + i, null, i))
			.toList();
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", null, null, "Released", "1999-03-31",
			List.of(), 8.2, 25000, new TmdbCredits(fifteen, List.of()), null, null, 136, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cast.length()").value(10))
			.andExpect(jsonPath("$.cast[9].name").value("Actor 9"));
	}

	@Test
	void getTitleDetailIncludesWatchProvidersForTheDefaultRegion() throws Exception {
		// The caller has no watch region configured — providers resolve for US (#270)
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", null, null, "Released", "1999-03-31",
			List.of(), 8.2, 25000, null,
			new TmdbWatchProviders(java.util.Map.of(
				"US", new TmdbRegionWatchProviders(List.of(
					new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0))),
				"GB", new TmdbRegionWatchProviders(List.of(
					new TmdbWatchProvider(9, "Prime Video", "/p.jpg", 1)))))
		, null, null, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.watchRegion").value("US"))
			.andExpect(jsonPath("$.watchProviders.length()").value(1))
			.andExpect(jsonPath("$.watchProviders[0].id").value(8))
			.andExpect(jsonPath("$.watchProviders[0].name").value("Netflix"))
			.andExpect(jsonPath("$.watchProviders[0].logoUrl").value("https://image.tmdb.org/t/p/w92/n.jpg"));
	}

	@Test
	void getTitleDetailUsesTheCallersWatchRegion() throws Exception {
		User gbUser = new User(1L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");
		gbUser.setWatchRegion("GB");
		when(userService.findById(1L)).thenReturn(gbUser);
		when(tmdbClient.getMovieDetail("603")).thenReturn(new TmdbMovieDetail(
			603, "The Matrix", null, null, "Released", "1999-03-31",
			List.of(), 8.2, 25000, null,
			new TmdbWatchProviders(java.util.Map.of(
				"US", new TmdbRegionWatchProviders(List.of(
					new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0))),
				"GB", new TmdbRegionWatchProviders(List.of(
					new TmdbWatchProvider(9, "Prime Video", "/p.jpg", 1)))))
		, null, null, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB")
			.param("type", "MOVIE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.watchRegion").value("GB"))
			.andExpect(jsonPath("$.watchProviders.length()").value(1))
			.andExpect(jsonPath("$.watchProviders[0].name").value("Prime Video"));
	}

	@Test
	void getTitleDetailReturnsTvDetailExcludingSpecials() throws Exception {
		when(tmdbClient.getTvDetail("1399")).thenReturn(new TmdbTvDetail(
			1399, 2, "Ended", "2011-04-17",
			List.of(
				new TmdbTvSeason(0, 0, "Specials", null, null, 5, null, null),
				new TmdbTvSeason(3625, 1, "Season 1", null, "/s1.jpg", 10, "2011-04-17", null),
				new TmdbTvSeason(3626, 2, "Season 2", null, null, 10, "2012-04-01", null)
			),
			"Game of Thrones", "Nine noble families fight for control.", "/got.jpg",
			List.of(new TmdbGenre(10765, "Sci-Fi & Fantasy")),
			8.4, 21000, null, null
		, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "1399")
			.param("externalSource", "TMDB")
			.param("type", "TV"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("TV"))
			.andExpect(jsonPath("$.name").value("Game of Thrones"))
			.andExpect(jsonPath("$.releaseDate").value("2011-04-17"))
			.andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/got.jpg"))
			.andExpect(jsonPath("$.status").value("Ended"))
			.andExpect(jsonPath("$.genres[0]").value("Sci-Fi & Fantasy"))
			.andExpect(jsonPath("$.voteAverage").value(8.4))
			.andExpect(jsonPath("$.voteCount").value(21000))
			.andExpect(jsonPath("$.seasonCount").value(2))
			.andExpect(jsonPath("$.seasons.length()").value(2))
			.andExpect(jsonPath("$.seasons[0].seasonNumber").value(1))
			.andExpect(jsonPath("$.seasons[1].seasonNumber").value(2));

		verify(tmdbClient).getTvDetail("1399");
	}

	@Test
	void getTitleDetailReturnsCastForTvShows() throws Exception {
		when(tmdbClient.getTvDetail("1399")).thenReturn(new TmdbTvDetail(
			1399, 1, "Ended", "2011-04-17",
			List.of(new TmdbTvSeason(3625, 1, "Season 1", null, null, 10, "2011-04-17", null)),
			"Game of Thrones", null, null, List.of(), 8.4, 21000,
			new TmdbCredits(List.of(
				new TmdbCastMember(1223786L, "Emilia Clarke", "Daenerys Targaryen", "/emilia.jpg", 0)),
				List.of()),
			null, null));

		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "1399")
			.param("externalSource", "TMDB")
			.param("type", "TV"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cast.length()").value(1))
			.andExpect(jsonPath("$.cast[0].name").value("Emilia Clarke"))
			.andExpect(jsonPath("$.cast[0].character").value("Daenerys Targaryen"))
			.andExpect(jsonPath("$.cast[0].profileUrl").value("https://image.tmdb.org/t/p/w185/emilia.jpg"));
	}

	@Test
	void getTitleDetailReturnsBadRequestForMissingType() throws Exception {
		mockMvc.perform(get("/api/titles/detail")
			.header("Authorization", "Bearer test-token")
			.param("externalId", "603")
			.param("externalSource", "TMDB"))
			.andExpect(status().isBadRequest());
	}
}
