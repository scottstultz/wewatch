package com.wewatch.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "watchlist_members")
public class WatchlistMember {

	@EmbeddedId
	private WatchlistMemberId id;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 32)
	private MemberRole role;

	@NotNull
	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@NotNull
	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	public WatchlistMember() {
	}

	public WatchlistMember(WatchlistMemberId id, MemberRole role, Instant joinedAt) {
		this.id = id;
		this.role = role;
		this.joinedAt = joinedAt;
		this.isDefault = false;
	}

	public WatchlistMember(WatchlistMemberId id, MemberRole role, Instant joinedAt, boolean isDefault) {
		this.id = id;
		this.role = role;
		this.joinedAt = joinedAt;
		this.isDefault = isDefault;
	}

	public WatchlistMemberId getId() {
		return id;
	}

	public void setId(WatchlistMemberId id) {
		this.id = id;
	}

	public MemberRole getRole() {
		return role;
	}

	public void setRole(MemberRole role) {
		this.role = role;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	public void setJoinedAt(Instant joinedAt) {
		this.joinedAt = joinedAt;
	}

	public boolean isDefault() {
		return isDefault;
	}

	public void setDefault(boolean isDefault) {
		this.isDefault = isDefault;
	}
}
