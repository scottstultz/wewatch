package com.wewatch.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;

public interface TitleRepository extends JpaRepository<Title, Long> {

	Optional<Title> findByExternalSourceAndExternalId(String externalSource, String externalId);

	@Query("SELECT t FROM Title t WHERE (:externalId IS NULL OR t.externalId = :externalId) AND (:externalSource IS NULL OR t.externalSource = :externalSource) AND (:type IS NULL OR t.type = :type) AND (:name IS NULL OR t.name = :name) ORDER BY t.id")
	List<Title> findByFilters(@Param("externalId") String externalId, @Param("externalSource") String externalSource, @Param("type") TitleType type, @Param("name") String name);
}
