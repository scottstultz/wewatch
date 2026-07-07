package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CachedKeywordListConverterTest {

	private final CachedKeywordListConverter converter = new CachedKeywordListConverter();

	@Test
	void roundTripPreservesNamesWithDelimiterCharacters() {
		// JSON storage exists precisely because names can carry commas and quotes —
		// the CSV format of IntegerListConverter would corrupt these
		List<CachedKeyword> keywords = List.of(
			new CachedKeyword(1, "new york city, usa"),
			new CachedKeyword(2, "\"chosen one\" trope"));

		assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(keywords)))
			.isEqualTo(keywords);
	}

	@Test
	void nullAndEmptyListsMapToNullColumn() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
	}

	@Test
	void nullAndBlankColumnsMapToEmptyList() {
		assertThat(converter.convertToEntityAttribute(null)).isEmpty();
		assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
	}
}
