package com.wewatch.api.dto;

import java.util.List;

public record SeasonDetailResponse(
	int seasonNumber,
	String name,
	String overview,
	String posterUrl,
	List<EpisodeResponse> episodes
) {
}
