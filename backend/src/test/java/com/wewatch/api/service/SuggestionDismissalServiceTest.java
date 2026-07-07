package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.repository.SuggestionDismissalRepository;

@ExtendWith(MockitoExtension.class)
class SuggestionDismissalServiceTest {

	@Mock private SuggestionDismissalRepository repository;

	private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");

	private SuggestionDismissalService service;

	@BeforeEach
	void setUp() {
		service = new SuggestionDismissalService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void dismissInsertsARowAtTheCurrentInstant() {
		service.dismiss(7L, "tmdb-1");

		verify(repository).insertDismissal(7L, "tmdb-1", NOW);
	}

	@Test
	void undismissDeletesTheUsersRow() {
		service.undismiss(7L, "tmdb-1");

		verify(repository).deleteByUserIdAndTmdbId(7L, "tmdb-1");
	}

	@Test
	void dismissedTmdbIdsUnionsAcrossTheGivenUsers() {
		when(repository.findTmdbIdsByUserIds(List.of(7L, 8L)))
			.thenReturn(List.of("tmdb-1", "tmdb-2", "tmdb-1"));

		assertThat(service.dismissedTmdbIds(List.of(7L, 8L)))
			.containsExactlyInAnyOrder("tmdb-1", "tmdb-2");
	}

	@Test
	void dismissedTmdbIdsSkipsTheQueryForNoUsers() {
		assertThat(service.dismissedTmdbIds(List.of())).isEqualTo(Set.of());

		verifyNoInteractions(repository);
	}
}
