package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.TitleRating;
import com.wewatch.api.repository.TitleRatingRepository;
import com.wewatch.api.repository.TitleRepository;

@ExtendWith(MockitoExtension.class)
class TitleRatingServiceTest {

	@Mock private TitleRatingRepository titleRatingRepository;
	@Mock private TitleRepository titleRepository;

	private static final Instant NOW = Instant.parse("2026-07-07T12:00:00Z");

	private TitleRatingService service() {
		return new TitleRatingService(titleRatingRepository, titleRepository,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void rateUpsertsWithTheClockTimestamp() {
		when(titleRepository.existsById(20L)).thenReturn(true);

		service().rate(10L, 20L, Rating.UP);

		verify(titleRatingRepository).upsertRating(10L, 20L, "UP", NOW);
	}

	@Test
	void ratingAMissingTitleThrowsNotFound() {
		when(titleRepository.existsById(20L)).thenReturn(false);

		assertThatThrownBy(() -> service().rate(10L, 20L, Rating.DOWN))
			.isInstanceOf(NoSuchElementException.class);
		verify(titleRatingRepository, never()).upsertRating(any(), any(), any(), any());
	}

	@Test
	void unrateDeletesTheCallersRow() {
		service().unrate(10L, 20L);

		verify(titleRatingRepository).deleteByUserIdAndTitleId(10L, 20L);
	}

	@Test
	void ratingsForMapsTitleIdToRating() {
		when(titleRatingRepository.findByUserIdAndTitleIds(10L, List.of(20L, 21L)))
			.thenReturn(List.of(rating(10L, 20L, Rating.UP), rating(10L, 21L, Rating.DOWN)));

		Map<Long, Rating> ratings = service().ratingsFor(10L, List.of(20L, 21L));

		assertThat(ratings).containsExactlyInAnyOrderEntriesOf(
			Map.of(20L, Rating.UP, 21L, Rating.DOWN));
	}

	@Test
	void ratingsForEmptyTitleIdsSkipsTheQuery() {
		assertThat(service().ratingsFor(10L, List.of())).isEmpty();
		verify(titleRatingRepository, never()).findByUserIdAndTitleIds(any(), any());
	}

	@Test
	void effectiveRatingsNetAcrossMembers() {
		// Title 20: both up → UP. Title 21: up + down cancel → absent (unrated).
		// Title 22: one down → DOWN. The documented shared-list choice (#273).
		when(titleRatingRepository.findByUserIdsAndTitleIds(List.of(7L, 8L), List.of(20L, 21L, 22L)))
			.thenReturn(List.of(
				rating(7L, 20L, Rating.UP), rating(8L, 20L, Rating.UP),
				rating(7L, 21L, Rating.UP), rating(8L, 21L, Rating.DOWN),
				rating(8L, 22L, Rating.DOWN)));

		Map<Long, Rating> effective = service().effectiveRatings(List.of(7L, 8L), List.of(20L, 21L, 22L));

		assertThat(effective).containsExactlyInAnyOrderEntriesOf(
			Map.of(20L, Rating.UP, 22L, Rating.DOWN));
	}

	@Test
	void effectiveRatingsWithNoUsersSkipsTheQuery() {
		assertThat(service().effectiveRatings(List.of(), List.of(20L))).isEmpty();
		verify(titleRatingRepository, never()).findByUserIdsAndTitleIds(any(), any());
	}

	private TitleRating rating(Long userId, Long titleId, Rating value) {
		TitleRating r = new TitleRating();
		r.setUserId(userId);
		r.setTitleId(titleId);
		r.setRating(value);
		return r;
	}
}
