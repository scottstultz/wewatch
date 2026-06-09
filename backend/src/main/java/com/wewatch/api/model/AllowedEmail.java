package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "allowed_emails")
public class AllowedEmail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Email
	@NotBlank
	@Size(max = 255)
	@Column(name = "email", nullable = false, unique = true, length = 255)
	private String email;

	@NotNull
	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	@Size(max = 255)
	@Column(name = "note", length = 255)
	private String note;

	public AllowedEmail() {
	}

	public AllowedEmail(String email, String note) {
		this.email = email;
		this.addedAt = Instant.now();
		this.note = note;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public Instant getAddedAt() {
		return addedAt;
	}

	public String getNote() {
		return note;
	}
}
