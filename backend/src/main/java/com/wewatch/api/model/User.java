package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(
	name = "users",
	uniqueConstraints = {
		@UniqueConstraint(name = "uq_users_email", columnNames = "email")
	}
)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Email
	@NotBlank
	@Size(max = 255)
	@Column(name = "email", nullable = false, unique = true, length = 255)
	private String email;

	@NotBlank
	@Size(max = 255)
	@Column(name = "display_name", nullable = false, length = 255)
	private String displayName;

	@NotNull
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@NotNull
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public User() {
	}

	public User(Long id, String email, String displayName, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.email = email;
		this.displayName = displayName;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
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
