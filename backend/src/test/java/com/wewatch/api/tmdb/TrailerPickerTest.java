package com.wewatch.api.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TrailerPickerTest {

	private static TmdbVideo video(String key, String site, String type, Boolean official, String publishedAt) {
		return new TmdbVideo(key, site, type, official, publishedAt);
	}

	private static TmdbVideos videos(TmdbVideo... items) {
		return new TmdbVideos(Arrays.asList(items));
	}

	@Test
	void buildsAYoutubeWatchUrlFromTheVideoKey() {
		TmdbVideos videos = videos(video("abc123", "YouTube", "Trailer", true, "2024-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos))
			.isEqualTo("https://www.youtube.com/watch?v=abc123");
	}

	@Test
	void prefersTheOfficialTrailerOverATeaser() {
		TmdbVideos videos = videos(
			video("teaser", "YouTube", "Teaser", true, "2024-06-01T00:00:00.000Z"),
			video("trailer", "YouTube", "Trailer", true, "2024-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=trailer");
	}

	@Test
	void prefersTheOfficialTrailerOverAnUnofficialOne() {
		TmdbVideos videos = videos(
			video("fan", "YouTube", "Trailer", false, "2024-06-01T00:00:00.000Z"),
			video("official", "YouTube", "Trailer", true, "2024-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=official");
	}

	@Test
	void prefersAnUnofficialTrailerOverAnOfficialTeaser() {
		TmdbVideos videos = videos(
			video("teaser", "YouTube", "Teaser", true, "2024-06-01T00:00:00.000Z"),
			video("trailer", "YouTube", "Trailer", false, "2024-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=trailer");
	}

	@Test
	void fallsBackToATeaserWhenThereIsNoTrailer() {
		TmdbVideos videos = videos(
			video("clip", "YouTube", "Clip", true, "2024-06-01T00:00:00.000Z"),
			video("teaser", "YouTube", "Teaser", false, "2024-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=teaser");
	}

	@Test
	void takesTheMostRecentlyPublishedAmongEquals() {
		TmdbVideos videos = videos(
			video("old", "YouTube", "Trailer", true, "2019-03-02T10:00:00.000Z"),
			video("newest", "YouTube", "Trailer", true, "2024-11-30T09:00:00.000Z"),
			video("middle", "YouTube", "Trailer", true, "2021-07-14T12:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=newest");
	}

	@Test
	void prefersADatedVideoOverAnUndatedOne() {
		TmdbVideos videos = videos(
			video("undated", "YouTube", "Trailer", true, null),
			video("dated", "YouTube", "Trailer", true, "2020-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=dated");
	}

	@Test
	void excludesVideosHostedOffYoutube() {
		TmdbVideos videos = videos(
			video("vimeo", "Vimeo", "Trailer", true, "2024-06-01T00:00:00.000Z"),
			video("tube", "YouTube", "Teaser", false, "2020-01-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).endsWith("=tube");
	}

	@Test
	void excludesVideoTypesThatArentTrailersOrTeasers() {
		TmdbVideos videos = videos(
			video("clip", "YouTube", "Clip", true, "2024-06-01T00:00:00.000Z"),
			video("featurette", "YouTube", "Featurette", true, "2024-06-01T00:00:00.000Z"),
			video("bts", "YouTube", "Behind the Scenes", true, "2024-06-01T00:00:00.000Z"),
			video("bloopers", "YouTube", "Bloopers", true, "2024-06-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).isNull();
	}

	@Test
	void returnsNullWhenTmdbSentNoVideosBlock() {
		assertThat(TrailerPicker.trailerUrl(null)).isNull();
	}

	@Test
	void returnsNullWhenTheResultsListIsNullOrEmpty() {
		assertThat(TrailerPicker.trailerUrl(new TmdbVideos(null))).isNull();
		assertThat(TrailerPicker.trailerUrl(new TmdbVideos(List.of()))).isNull();
	}

	@Test
	void skipsEntriesWithNoUsableKey() {
		TmdbVideos videos = videos(
			video(null, "YouTube", "Trailer", true, "2024-06-01T00:00:00.000Z"),
			video("  ", "YouTube", "Trailer", true, "2024-06-01T00:00:00.000Z"));

		assertThat(TrailerPicker.trailerUrl(videos)).isNull();
	}

	@Test
	void toleratesNullEntriesAndNullFieldsInTheList() {
		List<TmdbVideo> results = new ArrayList<>();
		results.add(null);
		results.add(video("ok", null, null, null, null));
		results.add(video("tube", "YouTube", "Trailer", null, null));

		assertThat(TrailerPicker.trailerUrl(new TmdbVideos(results))).endsWith("=tube");
	}
}
