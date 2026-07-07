package com.wewatch.api.model;

import java.util.Map;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// Per-region flatrate provider ids (#270), stored as one JSON object of
// region -> [provider ids] — availability is region-scoped, and a shared list's
// members (or a future second user) may sit in different regions, so caching a
// single region's ids would silently serve wrong data to everyone else.
@Converter
public class RegionProviderIdsConverter implements AttributeConverter<Map<String, List<Integer>>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, List<Integer>>> MAP_TYPE = new TypeReference<>() {};

	@Override
	public String convertToDatabaseColumn(Map<String, List<Integer>> attribute) {
		if (attribute == null) return null;
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize watch providers", e);
		}
	}

	@Override
	public Map<String, List<Integer>> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) return null;
		try {
			return MAPPER.readValue(dbData, MAP_TYPE);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to deserialize watch providers", e);
		}
	}
}
