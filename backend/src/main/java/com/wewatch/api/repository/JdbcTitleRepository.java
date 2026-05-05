package com.wewatch.api.repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.wewatch.api.model.Title;
import com.wewatch.api.model.TitleType;

@Repository
@Profile("local")
public class JdbcTitleRepository implements TitleRepository {

	private static final RowMapper<Title> ROW_MAPPER = (rs, rowNum) -> new Title(
		rs.getLong("id"),
		rs.getString("external_id"),
		rs.getString("external_source"),
		TitleType.valueOf(rs.getString("type")),
		rs.getString("name"),
		rs.getString("overview"),
		rs.getDate("release_date") == null ? null : rs.getDate("release_date").toLocalDate(),
		rs.getString("poster_url"),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant()
	);

	private final JdbcTemplate jdbcTemplate;

	public JdbcTitleRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Title create(Title title) {
		KeyHolder keyHolder = new GeneratedKeyHolder();

		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"""
				INSERT INTO titles (
					external_id,
					external_source,
					type,
					name,
					overview,
					release_date,
					poster_url,
					created_at,
					updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				new String[] {"id"}
			);
			statement.setString(1, title.getExternalId());
			statement.setString(2, title.getExternalSource());
			statement.setString(3, title.getType().name());
			statement.setString(4, title.getName());
			statement.setString(5, title.getOverview());
			statement.setObject(6, title.getReleaseDate() == null ? null : Date.valueOf(title.getReleaseDate()));
			statement.setString(7, title.getPosterUrl());
			statement.setTimestamp(8, Timestamp.from(title.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setTimestamp(9, Timestamp.from(title.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant()));
			return statement;
		}, keyHolder);

		Number generatedId = keyHolder.getKey();
		if (generatedId != null) {
			title.setId(generatedId.longValue());
		}

		return title;
	}

	@Override
	public Title update(Title title) {
		jdbcTemplate.update(
			"""
			UPDATE titles
			SET type = ?, name = ?, overview = ?, release_date = ?, poster_url = ?, updated_at = ?
			WHERE id = ?
			""",
			title.getType().name(),
			title.getName(),
			title.getOverview(),
			title.getReleaseDate() == null ? null : Date.valueOf(title.getReleaseDate()),
			title.getPosterUrl(),
			Timestamp.from(title.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant()),
			title.getId()
		);

		return title;
	}

	@Override
	public Optional<Title> findById(Long id) {
		List<Title> results = jdbcTemplate.query(
			"""
			SELECT id, external_id, external_source, type, name, overview, release_date, poster_url, created_at, updated_at
			FROM titles
			WHERE id = ?
			""",
			ROW_MAPPER,
			id
		);
		return results.stream().findFirst();
	}

	@Override
	public Optional<Title> findByExternalSourceAndExternalId(String externalSource, String externalId) {
		List<Title> results = jdbcTemplate.query(
			"""
			SELECT id, external_id, external_source, type, name, overview, release_date, poster_url, created_at, updated_at
			FROM titles
			WHERE external_source = ? AND external_id = ?
			""",
			ROW_MAPPER,
			externalSource,
			externalId
		);
		return results.stream().findFirst();
	}

	@Override
	public List<Title> findByFilters(String externalId, String externalSource, TitleType type, String name) {
		StringBuilder sql = new StringBuilder("""
			SELECT id, external_id, external_source, type, name, overview, release_date, poster_url, created_at, updated_at
			FROM titles
			WHERE 1 = 1
			""");
		List<Object> parameters = new ArrayList<>();

		if (externalId != null) {
			sql.append("AND external_id = ?\n");
			parameters.add(externalId);
		}
		if (externalSource != null) {
			sql.append("AND external_source = ?\n");
			parameters.add(externalSource);
		}
		if (type != null) {
			sql.append("AND type = ?\n");
			parameters.add(type.name());
		}
		if (name != null) {
			sql.append("AND name = ?\n");
			parameters.add(name);
		}

		sql.append("ORDER BY id");

		return jdbcTemplate.query(sql.toString(), ROW_MAPPER, parameters.toArray());
	}
}
