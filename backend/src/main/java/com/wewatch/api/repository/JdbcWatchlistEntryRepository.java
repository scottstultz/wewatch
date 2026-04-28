package com.wewatch.api.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
		rs.getString("title_name"),
		WatchStatus.valueOf(rs.getString("status")),
		(Integer) rs.getObject("rating"),
		rs.getString("notes"),
		rs.getTimestamp("date_added").toInstant(),
		rs.getDate("date_watched") == null ? null : rs.getDate("date_watched").toLocalDate()
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
				INSERT INTO watchlist_entries (title_name, status, rating, notes, date_added, date_watched)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
				Statement.RETURN_GENERATED_KEYS
			);
			statement.setString(1, watchlistEntry.getTitleName());
			statement.setString(2, watchlistEntry.getStatus().name());
			statement.setObject(3, watchlistEntry.getRating());
			statement.setString(4, watchlistEntry.getNotes());
			statement.setTimestamp(5, Timestamp.from(watchlistEntry.getDateAdded().atOffset(ZoneOffset.UTC).toInstant()));
			statement.setObject(6, watchlistEntry.getDateWatched());
			return statement;
		}, keyHolder);

		Number generatedId = keyHolder.getKey();
		if (generatedId != null) {
			watchlistEntry.setId(generatedId.longValue());
		}

		return watchlistEntry;
	}

	@Override
	public Optional<WatchlistEntry> findById(Long id) {
		List<WatchlistEntry> results = jdbcTemplate.query(
			"""
			SELECT id, title_name, status, rating, notes, date_added, date_watched
			FROM watchlist_entries
			WHERE id = ?
			""",
			ROW_MAPPER,
			id
		);
		return results.stream().findFirst();
	}

	@Override
	public List<WatchlistEntry> findAll() {
		return jdbcTemplate.query(
			"""
			SELECT id, title_name, status, rating, notes, date_added, date_watched
			FROM watchlist_entries
			ORDER BY date_added DESC, id DESC
			""",
			ROW_MAPPER
		);
	}

	@Override
	public WatchlistEntry update(WatchlistEntry watchlistEntry) {
		jdbcTemplate.update(
			"""
			UPDATE watchlist_entries
			SET title_name = ?, status = ?, rating = ?, notes = ?, date_added = ?, date_watched = ?
			WHERE id = ?
			""",
			watchlistEntry.getTitleName(),
			watchlistEntry.getStatus().name(),
			watchlistEntry.getRating(),
			watchlistEntry.getNotes(),
			Timestamp.from(watchlistEntry.getDateAdded().atOffset(ZoneOffset.UTC).toInstant()),
			watchlistEntry.getDateWatched(),
			watchlistEntry.getId()
		);

		return watchlistEntry;
	}

	@Override
	public void deleteById(Long id) {
		jdbcTemplate.update("DELETE FROM watchlist_entries WHERE id = ?", id);
	}
}
