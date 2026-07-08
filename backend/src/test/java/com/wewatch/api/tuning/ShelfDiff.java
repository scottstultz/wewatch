package com.wewatch.api.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wewatch.api.dto.SuggestionShelfResponse;
import com.wewatch.api.dto.TitleSearchResponse;

/**
 * Renders a single fixture's shelves and diffs two parameter sets over them
 * (#288): per-shelf titles entering/leaving, rank movement, overlap, and
 * summary stats (mean overlap, mean rank shift). Shelves are matched by their
 * label — seed selection and exploration rotation are driven by a
 * param-independent day-seeded RNG, so the same fixture and day produce the
 * same shelf labels across parameter sets, and a label present in only one set
 * is itself a signal (a seed shelf that folded into the catch-all, say).
 */
final class ShelfDiff {

	private ShelfDiff() {
	}

	/** Human-readable dump of one parameter set's shelves for one fixture. */
	static String render(String fixtureName, List<SuggestionShelfResponse> shelves) {
		StringBuilder sb = new StringBuilder();
		sb.append("● ").append(fixtureName).append("  (").append(shelves.size()).append(" shelves)\n");
		for (SuggestionShelfResponse shelf : shelves) {
			sb.append("  ┌ ").append(shelf.kind()).append("  \"").append(shelf.reason()).append("\"");
			if (shelf.providerFiltered()) sb.append("  [provider-filtered]");
			sb.append("  ×").append(shelf.titles().size()).append("\n");
			int rank = 1;
			for (TitleSearchResponse t : shelf.titles()) {
				sb.append("  │  ").append(String.format("%2d", rank++)).append(". ")
					.append(t.externalId()).append("  ").append(t.name() != null ? t.name() : "")
					.append(providerBadge(t)).append("\n");
			}
			sb.append("  └\n");
		}
		return sb.toString();
	}

	static String diff(String fixtureName, String nameA, List<SuggestionShelfResponse> a,
			String nameB, List<SuggestionShelfResponse> b) {
		Map<String, SuggestionShelfResponse> shelvesA = byLabel(a);
		Map<String, SuggestionShelfResponse> shelvesB = byLabel(b);
		Set<String> allLabels = new LinkedHashSet<>();
		allLabels.addAll(shelvesA.keySet());
		allLabels.addAll(shelvesB.keySet());

		StringBuilder sb = new StringBuilder();
		sb.append("● ").append(fixtureName).append("   ").append(nameA).append(" → ").append(nameB).append("\n");

		List<Double> overlaps = new ArrayList<>();
		List<Integer> rankShifts = new ArrayList<>();

		for (String label : allLabels) {
			SuggestionShelfResponse sa = shelvesA.get(label);
			SuggestionShelfResponse sb2 = shelvesB.get(label);
			if (sa == null) {
				sb.append("  + shelf appeared: \"").append(label).append("\" (")
					.append(sb2.titles().size()).append(" titles)\n");
				continue;
			}
			if (sb2 == null) {
				sb.append("  - shelf dropped:  \"").append(label).append("\" (")
					.append(sa.titles().size()).append(" titles)\n");
				continue;
			}

			List<String> idsA = ids(sa);
			List<String> idsB = ids(sb2);
			Set<String> setA = new LinkedHashSet<>(idsA);
			Set<String> setB = new LinkedHashSet<>(idsB);

			List<String> entering = new ArrayList<>(setB);
			entering.removeAll(setA);
			List<String> leaving = new ArrayList<>(setA);
			leaving.removeAll(setB);

			double overlap = overlap(setA, setB);
			overlaps.add(overlap);

			List<String> moves = new ArrayList<>();
			for (String id : idsA) {
				int pa = idsA.indexOf(id);
				int pb = idsB.indexOf(id);
				if (pb < 0) continue;
				int delta = pb - pa;
				rankShifts.add(Math.abs(delta));
				if (delta != 0) {
					moves.add(id + " " + (delta > 0 ? "↓" : "↑") + Math.abs(delta));
				}
			}

			if (entering.isEmpty() && leaving.isEmpty() && moves.isEmpty()) {
				sb.append("  = \"").append(label).append("\"  unchanged\n");
				continue;
			}
			sb.append("  ~ \"").append(label).append("\"  overlap ")
				.append(pct(overlap)).append("\n");
			if (!leaving.isEmpty()) sb.append("      out: ").append(String.join(", ", leaving)).append("\n");
			if (!entering.isEmpty()) sb.append("      in:  ").append(String.join(", ", entering)).append("\n");
			if (!moves.isEmpty()) sb.append("      moved: ").append(String.join(", ", moves)).append("\n");
		}

		sb.append("  ── summary: ")
			.append("shelves ").append(a.size()).append("→").append(b.size())
			.append(", mean shelf overlap ").append(pct(mean(overlaps)))
			.append(", mean |rank shift| ").append(String.format(Locale.ROOT, "%.2f", mean(rankShifts)))
			.append(", global title overlap ").append(pct(overlap(allIds(a), allIds(b))))
			.append("\n");
		return sb.toString();
	}

	private static Map<String, SuggestionShelfResponse> byLabel(List<SuggestionShelfResponse> shelves) {
		Map<String, SuggestionShelfResponse> map = new LinkedHashMap<>();
		for (SuggestionShelfResponse s : shelves) {
			// Kind-qualified so two kinds sharing a label never collide
			map.put(s.kind() + " | " + s.reason(), s);
		}
		return map;
	}

	private static List<String> ids(SuggestionShelfResponse shelf) {
		return shelf.titles().stream().map(TitleSearchResponse::externalId).toList();
	}

	private static Set<String> allIds(List<SuggestionShelfResponse> shelves) {
		Set<String> ids = new LinkedHashSet<>();
		shelves.forEach(s -> s.titles().forEach(t -> ids.add(t.externalId())));
		return ids;
	}

	private static double overlap(Set<String> a, Set<String> b) {
		if (a.isEmpty() && b.isEmpty()) return 1.0;
		Set<String> union = new LinkedHashSet<>(a);
		union.addAll(b);
		Set<String> inter = new LinkedHashSet<>(a);
		inter.retainAll(b);
		return (double) inter.size() / union.size();
	}

	private static String providerBadge(TitleSearchResponse t) {
		return t.providerIds() != null && !t.providerIds().isEmpty() ? "  ▸on:" + t.providerIds() : "";
	}

	private static double mean(List<? extends Number> xs) {
		return xs.isEmpty() ? 0.0 : xs.stream().mapToDouble(Number::doubleValue).average().orElse(0.0);
	}

	private static String pct(double v) {
		return String.format(Locale.ROOT, "%.0f%%", v * 100);
	}
}
