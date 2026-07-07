package com.wewatch.api.model;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// JSON rather than the CSV of IntegerListConverter: person names can contain
// any character, so a homegrown delimiter format would need escaping anyway.
@Converter
public class CachedPersonListConverter implements AttributeConverter<List<CachedPerson>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<List<CachedPerson>> LIST_TYPE = new TypeReference<>() {};

	@Override
	public String convertToDatabaseColumn(List<CachedPerson> attribute) {
		if (attribute == null || attribute.isEmpty()) return null;
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize cached people", e);
		}
	}

	@Override
	public List<CachedPerson> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) return Collections.emptyList();
		try {
			return MAPPER.readValue(dbData, LIST_TYPE);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to deserialize cached people", e);
		}
	}
}
