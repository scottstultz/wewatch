package com.wewatch.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.GenreCatalogResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.service.GenreCatalogService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// Read-only TMDB genre catalog (#381): the shared vocabulary for labelling the genre ids carried on
// watchlist entries and suggestion tiles. Not watchlist-scoped, so there is no membership check —
// authentication alone, from SecurityConfig's anyRequest().authenticated().
@RestController
@RequestMapping("/api/genres")
@Tag(name = "Genres", description = "Read-only TMDB genre catalog, used to label genre ids and to populate genre pickers.")
public class GenreController {

	private final GenreCatalogService genreCatalogService;

	public GenreController(GenreCatalogService genreCatalogService) {
		this.genreCatalogService = genreCatalogService;
	}

	@GetMapping
	@Operation(summary = "List TMDB's movie and TV genre catalogs",
		description = "Returns both catalogs, each sorted by name. They are kept separate because "
			+ "they genuinely differ: \"Action\" is movie id 28 and \"Action & Adventure\" is TV id "
			+ "10759, so a merged list would offer both with no way to tell them apart.\n\n"
			+ "Cached in-process for a day — the catalog changes on the order of years, so this "
			+ "costs no TMDB traffic per view.\n\n"
			+ "Degrades rather than fails: if TMDB is unreachable both lists come back empty with a "
			+ "200, which costs a caller its genre labels but not the page around them.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Catalogs returned; both lists empty if TMDB is unavailable"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header")
	})
	public GenreCatalogResponse getGenres() {
		return new GenreCatalogResponse(
			genreCatalogService.genresFor(TitleType.MOVIE),
			genreCatalogService.genresFor(TitleType.TV)
		);
	}
}
