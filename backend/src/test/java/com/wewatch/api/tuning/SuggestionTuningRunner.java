package com.wewatch.api.tuning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;

/**
 * Loads the fixture watchlists and drives the offline suggestion pipeline
 * (#288): render one parameter set, diff two parameter sets, or sweep the day
 * seed across a week. Fully deterministic — the same fixtures, parameters, and
 * day produce byte-identical reports on any machine. All output is returned as
 * text so callers can print it and/or persist it.
 */
final class SuggestionTuningRunner {

	private static final ObjectMapper MAPPER = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private final SyntheticCatalog catalog = new SyntheticCatalog();
	private final Map<String, List<TitleSearchResponse>> recorded = loadRecorded();
	private final List<FixtureWorld> fixtures = loadFixtures();

	List<FixtureWorld> fixtures() {
		return fixtures;
	}

	/** All fixtures' shelves under one parameter set, on the base day. */
	String renderParameterSet(TuningParameters params) {
		StringBuilder sb = new StringBuilder();
		sb.append(header("SHELVES — parameter set: " + params.name()));
		for (FixtureWorld world : fixtures) {
			List<SuggestionShelfResponse> shelves =
				world.compute(params.properties(), SyntheticCatalog.BASE_DAY, new ImpressionStore());
			sb.append(ShelfDiff.render(world.name(), shelves)).append("\n");
		}
		return sb.toString();
	}

	/** Diff of two parameter sets across all fixtures, on the base day. */
	String diffParameterSets(TuningParameters a, TuningParameters b) {
		StringBuilder sb = new StringBuilder();
		sb.append(header("DIFF — " + a.name() + " vs " + b.name()));
		for (FixtureWorld world : fixtures) {
			List<SuggestionShelfResponse> shelvesA =
				world.compute(a.properties(), SyntheticCatalog.BASE_DAY, new ImpressionStore());
			List<SuggestionShelfResponse> shelvesB =
				world.compute(b.properties(), SyntheticCatalog.BASE_DAY, new ImpressionStore());
			sb.append(ShelfDiff.diff(world.name(), a.name(), shelvesA, b.name(), shelvesB)).append("\n");
		}
		return sb.toString();
	}

	/**
	 * Rotation over {@code days} consecutive days from the base day, sharing one
	 * impression store per fixture so the #264 recency penalty accrues exactly
	 * as it would in production. Reports each day's global title overlap against
	 * the previous day and against day 1, so a healthy rotation shows overlap
	 * falling then recovering as titles age out of the penalty window.
	 */
	String sweepDays(TuningParameters params, int days) {
		StringBuilder sb = new StringBuilder();
		sb.append(header("DAY SWEEP — " + params.name() + " over " + days + " days from " + SyntheticCatalog.BASE_DAY));
		for (FixtureWorld world : fixtures) {
			ImpressionStore store = new ImpressionStore();
			sb.append("● ").append(world.name()).append("\n");
			Set<String> dayOne = null;
			Set<String> prev = null;
			for (int d = 0; d < days; d++) {
				LocalDate day = SyntheticCatalog.BASE_DAY.plusDays(d);
				List<SuggestionShelfResponse> shelves = world.compute(params.properties(), day, store);
				Set<String> ids = titleIds(shelves);
				String vsPrev = prev == null ? "  —" : "  vs prev " + pct(overlap(prev, ids));
				String vsFirst = dayOne == null ? "" : "  vs day1 " + pct(overlap(dayOne, ids));
				sb.append("    ").append(day).append("  ")
					.append(shelves.size()).append(" shelves, ")
					.append(ids.size()).append(" titles").append(vsPrev).append(vsFirst).append("\n");
				if (dayOne == null) dayOne = ids;
				prev = ids;
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	static Path writeReport(String fileName, String content) {
		try {
			Path dir = Path.of("target", "tuning");
			Files.createDirectories(dir);
			Path out = dir.resolve(fileName);
			Files.writeString(out, content);
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private List<FixtureWorld> loadFixtures() {
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:tuning/watchlists/*.json");
			List<FixtureWorld> loaded = new ArrayList<>();
			for (Resource r : resources) {
				try (var in = r.getInputStream()) {
					FixtureWatchlist fixture = MAPPER.readValue(in, FixtureWatchlist.class);
					loaded.add(new FixtureWorld(catalog, fixture, recorded));
				}
			}
			loaded.sort((x, y) -> x.name().compareTo(y.name()));
			if (loaded.isEmpty()) {
				throw new IllegalStateException("No fixtures found under tuning/watchlists/*.json");
			}
			return loaded;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Optional overlay of recorded real-TMDB responses (empty by default)
	private Map<String, List<TitleSearchResponse>> loadRecorded() {
		Map<String, List<TitleSearchResponse>> map = new LinkedHashMap<>();
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:tuning/recorded/*.json");
			for (Resource r : resources) {
				try (var in = r.getInputStream()) {
					RecordedFeed feed = MAPPER.readValue(in, RecordedFeed.class);
					if (feed.key() != null) map.put(feed.key(), feed.titles());
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return map;
	}

	private record RecordedFeed(String key, List<TitleSearchResponse> titles) {}

	private static Set<String> titleIds(List<SuggestionShelfResponse> shelves) {
		Set<String> ids = new java.util.LinkedHashSet<>();
		shelves.forEach(s -> s.titles().forEach(t -> ids.add(t.externalId())));
		return ids;
	}

	private static double overlap(Set<String> a, Set<String> b) {
		if (a.isEmpty() && b.isEmpty()) return 1.0;
		Set<String> union = new java.util.LinkedHashSet<>(a);
		union.addAll(b);
		Set<String> inter = new java.util.LinkedHashSet<>(a);
		inter.retainAll(b);
		return (double) inter.size() / union.size();
	}

	private static String pct(double v) {
		return String.format(Locale.ROOT, "%.0f%%", v * 100);
	}

	private static String header(String title) {
		String bar = "═".repeat(Math.max(12, title.length()));
		return bar + "\n" + title + "\n" + bar + "\n";
	}
}
