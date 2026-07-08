package com.wewatch.api.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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

	public Title findOrCreate(String externalSource, String externalId, Supplier<Title> candidateSupplier) {
		return titleRepository.findByExternalSourceAndExternalId(externalSource, externalId)
			.orElseGet(() -> saveNew(candidateSupplier.get()));
	}

	private Title saveNew(Title title) {
		Instant now = Instant.now();
		title.setCreatedAt(now);
		title.setUpdatedAt(now);

		validate(title);

		try {
			return titleRepository.save(title);
		} catch (DataIntegrityViolationException e) {
			// concurrent insert of the same external title — return the row that won the race
			return titleRepository.findByExternalSourceAndExternalId(title.getExternalSource(), title.getExternalId())
				.orElseThrow(() -> e);
		}
	}

	public Title findById(Long id) {
		return titleRepository.findById(id)
			.orElseThrow(() -> new NoSuchElementException("Title not found: " + id));
	}

	public Map<Long, Title> findByIds(Collection<Long> ids) {
		return titleRepository.findAllById(ids).stream()
			.collect(Collectors.toMap(Title::getId, t -> t));
	}

	// Optional variant for callers where "no row yet" is a normal state (the
	// pre-add detail page, #273) rather than an error
	public Optional<Title> findOptionalByExternalSourceAndExternalId(String externalSource, String externalId) {
		return titleRepository.findByExternalSourceAndExternalId(externalSource, externalId);
	}

	public Title findByExternalSourceAndExternalId(String externalSource, String externalId) {
		return titleRepository.findByExternalSourceAndExternalId(externalSource, externalId)
			.orElseThrow(() -> new NoSuchElementException(
				"Title not found for source " + externalSource + " and external id " + externalId
			));
	}

	public Page<Title> findByFilters(String externalId, String externalSource, TitleType type, String name, Pageable pageable) {
		return titleRepository.findByFilters(
			normalize(externalId),
			normalize(externalSource),
			type,
			normalize(name),
			pageable
		);
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
