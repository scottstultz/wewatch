package com.wewatch.api.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(
	name = "titles",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_titles_external_source_external_id", columnNames = {"external_source", "external_id"})
	}
)
public class Title {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotBlank
	@Size(max = 255)
	@Column(name = "external_id", nullable = false, length = 255)
	private String externalId;

	@NotBlank
	@Size(max = 100)
	@Column(name = "external_source", nullable = false, length = 100)
	private String externalSource;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 32)
	private TitleType type;

	@NotBlank
	@Size(max = 255)
	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Size(max = 4000)
	@Column(name = "overview", length = 4000)
	private String overview;

	@Column(name = "release_date")
	private LocalDate releaseDate;

	@Size(max = 2048)
	@Column(name = "poster_url", length = 2048)
	private String posterUrl;

	@NotNull
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@NotNull
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Title() {
	}

	public Title(
		Long id,
		String externalId,
		String externalSource,
		TitleType type,
		String name,
		String overview,
		LocalDate releaseDate,
		String posterUrl,
		Instant createdAt,
		Instant updatedAt
	) {
		this.id = id;
		this.externalId = externalId;
		this.externalSource = externalSource;
		this.type = type;
		this.name = name;
		this.overview = overview;
		this.releaseDate = releaseDate;
		this.posterUrl = posterUrl;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getExternalSource() {
		return externalSource;
	}

	public void setExternalSource(String externalSource) {
		this.externalSource = externalSource;
	}

	public TitleType getType() {
		return type;
	}

	public void setType(TitleType type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getOverview() {
		return overview;
	}

	public void setOverview(String overview) {
		this.overview = overview;
	}

	public LocalDate getReleaseDate() {
		return releaseDate;
	}

	public void setReleaseDate(LocalDate releaseDate) {
		this.releaseDate = releaseDate;
	}

	public String getPosterUrl() {
		return posterUrl;
	}

	public void setPosterUrl(String posterUrl) {
		this.posterUrl = posterUrl;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
