package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;

public interface TitleRepository {

	Title create(Title title);

	Optional<Title> findById(Long id);

	Optional<Title> findByExternalSourceAndExternalId(String externalSource, String externalId);

	List<Title> findByFilters(String externalId, String externalSource, TitleType type, String name);
}
