package com.wewatch.api.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class GenreIdsConverter implements AttributeConverter<List<Integer>, String> {

	@Override
	public String convertToDatabaseColumn(List<Integer> attribute) {
		if (attribute == null || attribute.isEmpty()) return null;
		return attribute.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	@Override
	public List<Integer> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) return Collections.emptyList();
		return Arrays.stream(dbData.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(Integer::parseInt)
			.toList();
	}
}
