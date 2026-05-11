package com.wewatch.api.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import com.wewatch.api.exception.DuplicateTitleException;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.repository.TitleRepository;

@Service
public class TitleService {

	private final TitleRepository titleRepository;
	private final Validator validator;

	public TitleService(TitleRepository titleRepository, Validator validator) {
		this.titleRepository = titleRepository;
		this.validator = validator;
	}

	public Title create(Title title) {
		Instant now = Instant.now();
		if (title.getCreatedAt() == null) {
			title.setCreatedAt(now);
		}
		if (title.getUpdatedAt() == null) {
			title.setUpdatedAt(now);
		}

		validate(title);

		titleRepository.findByExternalSourceAndExternalId(title.getExternalSource(), title.getExternalId())
			.ifPresent(existing -> {
				throw new DuplicateTitleException(title.getExternalSource(), title.getExternalId());
			});

		return titleRepository.save(title);
	}

	public Title findById(Long id) {
		return titleRepository.findById(id)
			.orElseThrow(() -> new NoSuchElementException("Title not found: " + id));
	}

	public Title findByExternalSourceAndExternalId(String externalSource, String externalId) {
		return titleRepository.findByExternalSourceAndExternalId(externalSource, externalId)
			.orElseThrow(() -> new NoSuchElementException(
				"Title not found for source " + externalSource + " and external id " + externalId
			));
	}

	public List<Title> findByFilters(String externalId, String externalSource, TitleType type, String name) {
		return titleRepository.findByFilters(
			normalize(externalId),
			normalize(externalSource),
			type,
			normalize(name)
		);
	}

	public Title update(
		Long id,
		String name,
		String overview,
		LocalDate releaseDate,
		String posterUrl,
		TitleType type
	) {
		Title existingTitle = findById(id);

		if (name != null) {
			existingTitle.setName(name);
		}
		if (overview != null) {
			existingTitle.setOverview(overview);
		}
		if (releaseDate != null) {
			existingTitle.setReleaseDate(releaseDate);
		}
		if (posterUrl != null) {
			existingTitle.setPosterUrl(posterUrl);
		}
		if (type != null) {
			existingTitle.setType(type);
		}
		existingTitle.setUpdatedAt(Instant.now());

		validate(existingTitle);

		return titleRepository.save(existingTitle);
	}

	private void validate(Title title) {
		Set<ConstraintViolation<Title>> violations = validator.validate(title);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}

	private String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
	}
}
