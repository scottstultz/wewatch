package com.wewatch.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.PersonDetailResponse;
import com.wewatch.api.tmdb.PersonCreditsMapper;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbDates;
import com.wewatch.api.tmdb.TmdbPersonDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/people")
@Tag(name = "People", description = "TMDB-backed person detail and filmography.")
public class PersonController {

	private final TmdbClient tmdbClient;

	public PersonController(TmdbClient tmdbClient) {
		this.tmdbClient = tmdbClient;
	}

	@GetMapping("/{personId}")
	@Operation(summary = "Get a person's bio and filmography",
		description = "Returns the person's biography plus their acting credits across movies and "
			+ "TV (#304), sourced from TMDB's combined_credits. Credits are deduped, restricted to "
			+ "titles with a poster, stripped of talk-show \"Self\" appearances, and sorted by "
			+ "popularity. This calls TMDB live.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Person detail returned"),
		@ApiResponse(responseCode = "401", description = "Missing or invalid Authorization header"),
		@ApiResponse(responseCode = "502", description = "TMDB API call failed")
	})
	public PersonDetailResponse getPerson(
		@Parameter(description = "TMDB person id, as carried by the cast tiles on title details")
		@PathVariable int personId
	) {
		TmdbPersonDetail detail = tmdbClient.getPersonDetail(personId);

		return new PersonDetailResponse(
			detail.id(),
			detail.name(),
			detail.biography(),
			TmdbClient.profileUrl(detail.profilePath()),
			detail.knownForDepartment(),
			TmdbDates.parse(detail.birthday()),
			detail.placeOfBirth(),
			PersonCreditsMapper.toCredits(detail.combinedCredits())
		);
	}

}
