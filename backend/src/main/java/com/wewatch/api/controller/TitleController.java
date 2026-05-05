package com.wewatch.api.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wewatch.api.dto.TitleCreateRequest;
import com.wewatch.api.dto.TitleResponse;
import com.wewatch.api.dto.TitleUpdateRequest;
import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.service.TitleService;

@RestController
@Profile("local")
@RequestMapping("/api/titles")
public class TitleController {

	private final TitleService titleService;

	public TitleController(TitleService titleService) {
		this.titleService = titleService;
	}

	@PostMapping
	public ResponseEntity<TitleResponse> createTitle(@Valid @RequestBody TitleCreateRequest request) {
		Title createdTitle = titleService.create(new Title(
			null,
			request.externalId(),
			request.externalSource(),
			request.type(),
			request.name(),
			request.overview(),
			request.releaseDate(),
			request.posterUrl(),
			null,
			null
		));

		return ResponseEntity
			.created(URI.create("/api/titles/" + createdTitle.getId()))
			.body(toResponse(createdTitle));
	}

	@GetMapping
	public List<TitleResponse> getTitles(
		@RequestParam(required = false) String externalId,
		@RequestParam(required = false) String externalSource,
		@RequestParam(required = false) TitleType type,
		@RequestParam(required = false) String name
	) {
		return titleService.findByFilters(externalId, externalSource, type, name).stream()
			.map(this::toResponse)
			.toList();
	}

	@GetMapping("/{titleId}")
	public TitleResponse getTitle(@PathVariable Long titleId) {
		return toResponse(titleService.findById(titleId));
	}

	@PatchMapping("/{titleId}")
	public TitleResponse updateTitle(@PathVariable Long titleId, @Valid @RequestBody TitleUpdateRequest request) {
		return toResponse(titleService.update(
			titleId,
			request.name(),
			request.overview(),
			request.releaseDate(),
			request.posterUrl(),
			request.type()
		));
	}

	private TitleResponse toResponse(Title title) {
		return new TitleResponse(
			title.getId(),
			title.getExternalId(),
			title.getExternalSource(),
			title.getType(),
			title.getName(),
			title.getOverview(),
			title.getReleaseDate(),
			title.getPosterUrl(),
			title.getCreatedAt(),
			title.getUpdatedAt()
		);
	}
}
