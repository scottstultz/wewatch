package com.wewatch.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record SeasonDetailResponse(
	@Schema(description = "1-based season number; season 0 (\"Specials\") is excluded")
	int seasonNumber,
	String name,
	String overview,
	String posterUrl,
	List<EpisodeResponse> episodes
) {
}
