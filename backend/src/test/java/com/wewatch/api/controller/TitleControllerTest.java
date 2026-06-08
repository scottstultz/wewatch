package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

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

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.DuplicateTitleException;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.TitleService;
import com.wewatch.api.service.UserService;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbTvEpisode;
import com.wewatch.api.tmdb.TmdbTvSeason;

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
	void createTitleReturnsCreatedTitle() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Title createdTitle = new Title(
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

		when(titleService.create(any(Title.class))).thenReturn(createdTitle);

		mockMvc.perform(
			post("/api/titles")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE",
					  "name": "The Matrix",
					  "overview": "A computer hacker learns about the true nature of reality.",
					  "releaseDate": "1999-03-31",
					  "posterUrl": "https://image.tmdb.org/t/p/w500/matrix.jpg"
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/titles/1"))
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

		verify(titleService).create(any(Title.class));
	}

	@Test
	void createTitleReturnsBadRequestForInvalidPayload() throws Exception {
		mockMvc.perform(
			post("/api/titles")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "",
					  "externalSource": "",
					  "type": null,
					  "name": ""
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
	}

	@Test
	void createTitleReturnsConflictForDuplicateExternalTitle() throws Exception {
		when(titleService.create(any(Title.class))).thenThrow(new DuplicateTitleException("TMDB", "603"));

		mockMvc.perform(
			post("/api/titles")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "externalId": "603",
					  "externalSource": "TMDB",
					  "type": "MOVIE",
					  "name": "The Matrix"
					}
					""")
		)
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message").value("Title already exists for source TMDB and external id 603"));
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
	void updateTitleReturnsUpdatedTitle() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-29T12:00:00Z");
		Title updatedTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.TV,
			"The Matrix",
			"Updated overview",
			LocalDate.parse("1999-03-31"),
			"https://example.com/updated.jpg",
			createdAt,
			updatedAt
		);

		when(titleService.update(
			1L,
			"The Matrix",
			"Updated overview",
			LocalDate.parse("1999-03-31"),
			"https://example.com/updated.jpg",
			TitleType.TV
		)).thenReturn(updatedTitle);

		mockMvc.perform(
			patch("/api/titles/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "The Matrix",
					  "overview": "Updated overview",
					  "releaseDate": "1999-03-31",
					  "posterUrl": "https://example.com/updated.jpg",
					  "type": "TV"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.type").value("TV"))
			.andExpect(jsonPath("$.name").value("The Matrix"))
			.andExpect(jsonPath("$.overview").value("Updated overview"))
			.andExpect(jsonPath("$.releaseDate").value("1999-03-31"))
			.andExpect(jsonPath("$.posterUrl").value("https://example.com/updated.jpg"))
			.andExpect(jsonPath("$.createdAt").value("2026-04-28T12:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-04-29T12:00:00Z"));

		verify(titleService).update(
			1L,
			"The Matrix",
			"Updated overview",
			LocalDate.parse("1999-03-31"),
			"https://example.com/updated.jpg",
			TitleType.TV
		);
	}

	@Test
	void updateTitleSupportsPartialPayload() throws Exception {
		Instant createdAt = Instant.parse("2026-04-28T12:00:00Z");
		Instant updatedAt = Instant.parse("2026-04-29T12:00:00Z");
		Title updatedTitle = new Title(
			1L,
			"603",
			"TMDB",
			TitleType.MOVIE,
			"The Matrix Reloaded",
			null,
			LocalDate.parse("1999-03-31"),
			null,
			createdAt,
			updatedAt
		);

		when(titleService.update(1L, "The Matrix Reloaded", null, null, null, null)).thenReturn(updatedTitle);

		mockMvc.perform(
			patch("/api/titles/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "The Matrix Reloaded"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.externalId").value("603"))
			.andExpect(jsonPath("$.externalSource").value("TMDB"))
			.andExpect(jsonPath("$.name").value("The Matrix Reloaded"));

		verify(titleService).update(1L, "The Matrix Reloaded", null, null, null, null);
	}

	@Test
	void updateTitleReturnsNotFoundWhenMissing() throws Exception {
		when(titleService.update(42L, "The Matrix", null, null, null, null))
			.thenThrow(new NoSuchElementException("Title not found: 42"));

		mockMvc.perform(
			patch("/api/titles/42")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "The Matrix"
					}
					""")
		)
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Title not found: 42"));
	}

	@Test
	void updateTitleReturnsBadRequestForInvalidPayload() throws Exception {
		mockMvc.perform(
			patch("/api/titles/1")
				.header("Authorization", "Bearer test-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": ""
					}
					""")
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"));
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
				"https://image.tmdb.org/t/p/w500/poster.jpg"
			)
		);

		when(tmdbClient.search("inception", null)).thenReturn(searchResults);

		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token")
			.param("q", "inception"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].externalId").value("27205"))
			.andExpect(jsonPath("$[0].externalSource").value("TMDB"))
			.andExpect(jsonPath("$[0].type").value("MOVIE"))
			.andExpect(jsonPath("$[0].name").value("Inception"))
			.andExpect(jsonPath("$[0].releaseDate").value("2010-07-16"))
			.andExpect(jsonPath("$[0].posterUrl").value("https://image.tmdb.org/t/p/w500/poster.jpg"));

		verify(tmdbClient).search("inception", null);
	}

	@Test
	void searchTitlesReturnsEmptyListWhenNoResultsFound() throws Exception {
		when(tmdbClient.search("xyznotafilm", null)).thenReturn(List.of());

		mockMvc.perform(get("/api/titles/search")
			.header("Authorization", "Bearer test-token")
			.param("q", "xyznotafilm"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
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
		when(tmdbClient.getSeasons("1399")).thenReturn(List.of(
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
		when(tmdbClient.getSeasonDetail("1399", 1)).thenReturn(new TmdbTvSeason(
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
}
