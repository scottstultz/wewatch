package com.wewatch.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CachedPersonListConverterTest {

	private final CachedPersonListConverter converter = new CachedPersonListConverter();

	@Test
	void roundTripPreservesNamesWithDelimiterCharacters() {
		// JSON storage exists precisely because names can carry commas and quotes —
		// the CSV format of IntegerListConverter would corrupt these
		List<CachedPerson> people = List.of(
			new CachedPerson(1, "Robert Downey, Jr."),
			new CachedPerson(2, "Dwayne \"The Rock\" Johnson"));

		assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(people)))
			.isEqualTo(people);
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
