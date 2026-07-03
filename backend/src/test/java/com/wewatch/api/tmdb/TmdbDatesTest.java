package com.wewatch.api.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class TmdbDatesTest {

	@Test
	void parsesValidIsoDate() {
		assertThat(TmdbDates.parse("2010-07-16")).isEqualTo(LocalDate.of(2010, 7, 16));
	}

	@Test
	void returnsNullForNull() {
		assertThat(TmdbDates.parse(null)).isNull();
	}

	@Test
	void returnsNullForBlank() {
		assertThat(TmdbDates.parse("  ")).isNull();
	}

	@Test
	void returnsNullForMalformedDate() {
		assertThat(TmdbDates.parse("2010-7-16")).isNull();
		assertThat(TmdbDates.parse("not-a-date")).isNull();
		assertThat(TmdbDates.parse("2010-13-45")).isNull();
	}
}
