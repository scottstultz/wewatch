package com.wewatch.api.repository;

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

import com.wewatch.api.model.User;

@Repository
@Profile("local")
public class JdbcUserRepository implements UserRepository {

	private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
		rs.getLong("id"),
		rs.getString("email"),
		rs.getString("display_name"),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant()
	);

	private final JdbcTemplate jdbcTemplate;

	public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public User create(User user) {
		KeyHolder keyHolder = new GeneratedKeyHolder();

		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"""
				INSERT INTO users (email, display_name, created_at, updated_at)
				VALUES (?, ?, ?, ?)
				""",
				new String[] {"id"}
			);
			statement.setString(1, user.getEmail());
			statement.setString(2, user.getDisplayName());
			statement.setTimestamp(3, Timestamp.from(user.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setTimestamp(4, Timestamp.from(user.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant()));
			return statement;
		}, keyHolder);

		Number generatedId = keyHolder.getKey();
		if (generatedId != null) {
			user.setId(generatedId.longValue());
		}

		return user;
	}

	@Override
	public Optional<User> findById(Long id) {
		List<User> results = jdbcTemplate.query(
			"""
			SELECT id, email, display_name, created_at, updated_at
			FROM users
			WHERE id = ?
			""",
			ROW_MAPPER,
			id
		);
		return results.stream().findFirst();
	}

	@Override
	public Optional<User> findByEmail(String email) {
		List<User> results = jdbcTemplate.query(
			"""
			SELECT id, email, display_name, created_at, updated_at
			FROM users
			WHERE email = ?
			""",
			ROW_MAPPER,
			email
		);
		return results.stream().findFirst();
	}

	@Override
	public List<User> findByFilters(String email, String displayName) {
		StringBuilder sql = new StringBuilder("""
			SELECT id, email, display_name, created_at, updated_at
			FROM users
			WHERE 1 = 1
			""");
		List<Object> parameters = new ArrayList<>();

		if (email != null) {
			sql.append("AND email = ?\n");
			parameters.add(email);
		}
		if (displayName != null) {
			sql.append("AND display_name = ?\n");
			parameters.add(displayName);
		}

		sql.append("ORDER BY id");

		return jdbcTemplate.query(sql.toString(), ROW_MAPPER, parameters.toArray());
	}
}
