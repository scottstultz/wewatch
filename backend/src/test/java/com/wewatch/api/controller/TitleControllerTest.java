package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.exception.DuplicateTitleException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.service.TitleService;

@WebMvcTest(TitleController.class)
@ActiveProfiles("local")
class TitleControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private TitleService titleService;

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

		mockMvc.perform(get("/api/titles/1"))
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

		when(titleService.findByFilters("603", "TMDB", null, null)).thenReturn(List.of(existingTitle));

		mockMvc.perform(
			get("/api/titles")
				.param("externalId", "603")
				.param("externalSource", "TMDB")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].externalId").value("603"))
			.andExpect(jsonPath("$[0].externalSource").value("TMDB"))
			.andExpect(jsonPath("$[0].type").value("MOVIE"))
			.andExpect(jsonPath("$[0].name").value("The Matrix"));

		verify(titleService).findByFilters("603", "TMDB", null, null);
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

		when(titleService.findByFilters(null, null, null, "The Matrix")).thenReturn(List.of(existingTitle));

		mockMvc.perform(get("/api/titles").param("name", "The Matrix"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].name").value("The Matrix"));

		verify(titleService).findByFilters(null, null, null, "The Matrix");
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

		when(titleService.findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix")).thenReturn(List.of(existingTitle));

		mockMvc.perform(
			get("/api/titles")
				.param("externalId", "603")
				.param("externalSource", "TMDB")
				.param("type", "MOVIE")
				.param("name", "The Matrix")
		)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].type").value("MOVIE"))
			.andExpect(jsonPath("$[0].name").value("The Matrix"));

		verify(titleService).findByFilters("603", "TMDB", TitleType.MOVIE, "The Matrix");
	}

	@Test
	void getTitlesReturnsEmptyListWhenNoTitlesMatch() throws Exception {
		when(titleService.findByFilters(null, null, null, "Missing Title")).thenReturn(List.of());

		mockMvc.perform(get("/api/titles").param("name", "Missing Title"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getTitleReturnsNotFoundWhenMissing() throws Exception {
		when(titleService.findById(42L)).thenThrow(new NoSuchElementException("Title not found: 42"));

		mockMvc.perform(get("/api/titles/42"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Title not found: 42"));
	}
}
