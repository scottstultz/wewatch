package com.wewatch.api.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.wewatch.api.model.WatchStatus;
import com.wewatch.api.model.WatchlistEntry;

@Repository
@Profile("local")
public class JdbcWatchlistEntryRepository implements WatchlistEntryRepository {

	private static final RowMapper<WatchlistEntry> ROW_MAPPER = (rs, rowNum) -> new WatchlistEntry(
		rs.getLong("id"),
		rs.getLong("user_id"),
		rs.getLong("title_id"),
		WatchStatus.valueOf(rs.getString("status")),
		rs.getTimestamp("added_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant(),
		rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
		rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
	);

	private final JdbcTemplate jdbcTemplate;

	public JdbcWatchlistEntryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public WatchlistEntry create(WatchlistEntry watchlistEntry) {
		KeyHolder keyHolder = new GeneratedKeyHolder();

		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"""
				INSERT INTO watchlist_entries (user_id, title_id, status, added_at, updated_at, started_at, completed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				new String[] {"id"}
			);
			statement.setLong(1, watchlistEntry.getUserId());
			statement.setLong(2, watchlistEntry.getTitleId());
			statement.setString(3, watchlistEntry.getStatus().name());
			statement.setTimestamp(4, Timestamp.from(watchlistEntry.getAddedAt().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setTimestamp(5, Timestamp.from(watchlistEntry.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setTimestamp(6, watchlistEntry.getStartedAt() == null ? null : Timestamp.from(watchlistEntry.getStartedAt().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setTimestamp(7, watchlistEntry.getCompletedAt() == null ? null : Timestamp.from(watchlistEntry.getCompletedAt().atOffset(ZoneOffset.UTC).toInstant()));
			return statement;
		}, keyHolder);

		Number generatedId = keyHolder.getKey();
		if (generatedId != null) {
			watchlistEntry.setId(generatedId.longValue());
		}

		return watchlistEntry;
	}

	@Override
	public Optional<WatchlistEntry> findById(Long userId, Long id) {
		List<WatchlistEntry> results = jdbcTemplate.query(
			"""
			SELECT id, user_id, title_id, status, added_at, updated_at, started_at, completed_at
			FROM watchlist_entries
			WHERE user_id = ? AND id = ?
			""",
			ROW_MAPPER,
			userId,
			id
		);
		return results.stream().findFirst();
	}

	@Override
	public List<WatchlistEntry> findAllByUserId(Long userId) {
		return jdbcTemplate.query(
			"""
			SELECT id, user_id, title_id, status, added_at, updated_at, started_at, completed_at
			FROM watchlist_entries
			WHERE user_id = ?
			ORDER BY added_at DESC, id DESC
			""",
			ROW_MAPPER,
			userId
		);
	}

	@Override
	public WatchlistEntry update(WatchlistEntry watchlistEntry) {
		jdbcTemplate.update(
			"""
			UPDATE watchlist_entries
			SET title_id = ?, status = ?, added_at = ?, updated_at = ?, started_at = ?, completed_at = ?
			WHERE user_id = ? AND id = ?
			""",
			watchlistEntry.getTitleId(),
			watchlistEntry.getStatus().name(),
			Timestamp.from(watchlistEntry.getAddedAt().atOffset(ZoneOffset.UTC).toInstant()),
			Timestamp.from(watchlistEntry.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant()),
			watchlistEntry.getStartedAt() == null ? null : Timestamp.from(watchlistEntry.getStartedAt().atOffset(ZoneOffset.UTC).toInstant()),
			watchlistEntry.getCompletedAt() == null ? null : Timestamp.from(watchlistEntry.getCompletedAt().atOffset(ZoneOffset.UTC).toInstant()),
			watchlistEntry.getUserId(),
			watchlistEntry.getId()
		);

		return watchlistEntry;
	}

	@Override
	public void deleteById(Long userId, Long id) {
		jdbcTemplate.update("DELETE FROM watchlist_entries WHERE user_id = ? AND id = ?", userId, id);
	}
}
