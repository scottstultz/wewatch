package com.wewatch.api.service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.wewatch.api.exception.DuplicateTitleException;
import com.wewatch.api.model.Title;
import com.wewatch.api.repository.TitleRepository;

@Service
@Profile("local")
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

		return titleRepository.create(title);
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

	private void validate(Title title) {
		Set<ConstraintViolation<Title>> violations = validator.validate(title);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}
