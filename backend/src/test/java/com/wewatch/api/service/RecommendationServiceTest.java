package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.TitleSearchResponse;
import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

	@Mock private TmdbClient tmdbClient;

	private RecommendationService service() {
		return new RecommendationService(tmdbClient, 12L);
	}

	private static TitleSearchResponse title(String externalId, String name) {
		return new TitleSearchResponse(externalId, "TMDB", TitleType.MOVIE, name,
			"overview", LocalDate.parse("2010-01-01"), null, null);
	}

	@Test
	void returnsRecommendationsWithoutFallbackWhenPlentiful() {
		// Six recommendations is at/above the top-up threshold — similar not consulted
		List<TitleSearchResponse> recs = IntStream.rangeClosed(1, 6)
			.mapToObj(i -> title(String.valueOf(1000 + i), "Rec " + i))
			.toList();
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1)).thenReturn(recs);

		List<TitleSearchResponse> result = service().recommendationsFor(TitleType.MOVIE, "603");

		assertThat(result).extracting(TitleSearchResponse::externalId)
			.containsExactly("1001", "1002", "1003", "1004", "1005", "1006");
		verify(tmdbClient, never()).getSimilar(any(), anyString(), anyInt());
	}

	@Test
	void topsUpWithSimilarWhenRecommendationsAreSparse() {
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1))
			.thenReturn(List.of(title("1001", "Rec 1"), title("1002", "Rec 2")));
		when(tmdbClient.getSimilar(TitleType.MOVIE, "603", 1))
			.thenReturn(List.of(title("2001", "Sim 1"), title("2002", "Sim 2")));

		List<TitleSearchResponse> result = service().recommendationsFor(TitleType.MOVIE, "603");

		// Recommendations first, similar appended
		assertThat(result).extracting(TitleSearchResponse::externalId)
			.containsExactly("1001", "1002", "2001", "2002");
	}

	@Test
	void dedupsAcrossFeedsAndDropsTheAnchorTitle() {
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1))
			.thenReturn(List.of(title("1001", "Rec 1"), title("1002", "Rec 2")));
		when(tmdbClient.getSimilar(TitleType.MOVIE, "603", 1))
			// 1001 duplicates a recommendation; 603 is the anchor itself
			.thenReturn(List.of(title("1001", "Rec 1 dup"), title("603", "The anchor"), title("2001", "Sim 1")));

		List<TitleSearchResponse> result = service().recommendationsFor(TitleType.MOVIE, "603");

		assertThat(result).extracting(TitleSearchResponse::externalId)
			.containsExactly("1001", "1002", "2001");
	}

	@Test
	void returnsEmptyWhenBothFeedsAreEmpty() {
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1)).thenReturn(List.of());
		when(tmdbClient.getSimilar(TitleType.MOVIE, "603", 1)).thenReturn(List.of());

		assertThat(service().recommendationsFor(TitleType.MOVIE, "603")).isEmpty();
	}

	@Test
	void cachesPerTitleAcrossCalls() {
		List<TitleSearchResponse> recs = IntStream.rangeClosed(1, 6)
			.mapToObj(i -> title(String.valueOf(1000 + i), "Rec " + i))
			.toList();
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1)).thenReturn(recs);

		RecommendationService service = service();
		service.recommendationsFor(TitleType.MOVIE, "603");
		service.recommendationsFor(TitleType.MOVIE, "603");

		verify(tmdbClient, times(1)).getRecommendations(TitleType.MOVIE, "603", 1);
	}

	@Test
	void tmdbFailurePropagatesAndIsNotCached() {
		when(tmdbClient.getRecommendations(TitleType.MOVIE, "603", 1))
			.thenThrow(new TmdbApiException("TMDB recommendations failed", new RuntimeException()));

		RecommendationService service = service();

		assertThatThrownBy(() -> service.recommendationsFor(TitleType.MOVIE, "603"))
			.isInstanceOf(TmdbApiException.class);
		// A throwing loader is not stored — the next call retries TMDB rather than
		// serving a poisoned empty result
		assertThatThrownBy(() -> service.recommendationsFor(TitleType.MOVIE, "603"))
			.isInstanceOf(TmdbApiException.class);
		verify(tmdbClient, times(2)).getRecommendations(TitleType.MOVIE, "603", 1);
	}
}
