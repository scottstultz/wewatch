package com.wewatch.api.repository;

import java.util.Optional;

import com.wewatch.api.model.Title;

public interface TitleRepository {

	Title create(Title title);

	Optional<Title> findById(Long id);

	Optional<Title> findByExternalSourceAndExternalId(String externalSource, String externalId);
}
