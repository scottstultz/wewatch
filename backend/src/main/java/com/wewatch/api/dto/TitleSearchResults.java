package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The Discover search payload (#356): titles and people from one TMDB
 * {@code /search/multi} call. Kept as two separate lists — rather than
 * interleaved — so the client can render a slim "People" row above the title
 * grid. Movie/TV {@code type}-filtered searches return no people.
 */
public record TitleSearchResults(
	List<TitleSearchResponse> titles,
	@Schema(description = "Person hits from a multi search, ranked by TMDB popularity; empty on "
		+ "movie/tv type-filtered searches")
	List<PersonSearchResult> people
) {
}
