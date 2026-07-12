package com.wewatch.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.model.Rating;
import com.wewatch.api.model.TitleRating;
import com.wewatch.api.repository.TitleRatingRepository;
import com.wewatch.api.repository.TitleRepository;

// Per-user thumbs up/down ratings (#273). User-scoped like dismissals (#268)
// — a rating follows the user across every list — but keyed on the internal
// title id (ratings only make sense for titles the user has engaged with,
// which always have a row) and tri-state: up, down, or cleared back to unrated.
@Service
public class TitleRatingService {

	private final TitleRatingRepository titleRatingRepository;
	private final TitleRepository titleRepository;
	private final Clock clock;

	public TitleRatingService(
		TitleRatingRepository titleRatingRepository,
		TitleRepository titleRepository,
		Clock clock
	) {
		this.titleRatingRepository = titleRatingRepository;
		this.titleRepository = titleRepository;
		this.clock = clock;
	}

	@Transactional
	public void rate(Long userId, Long titleId, Rating rating) {
		// 404 before the FK violation would turn a bad title id into a 500
		if (!titleRepository.existsById(titleId)) {
			throw new NoSuchElementException("Title not found: " + titleId);
		}
		titleRatingRepository.upsertRating(userId, titleId, rating.name(), Instant.now(clock));
	}

	// Clearing an absent rating is a no-op, not an error — idempotent like
	// dismissal undo
	@Transactional
	public void unrate(Long userId, Long titleId) {
		titleRatingRepository.deleteByUserIdAndTitleId(userId, titleId);
	}

	// One user's ratings for a batch of titles, for the myRating response field
	public Map<Long, Rating> ratingsFor(Long userId, Collection<Long> titleIds) {
		if (titleIds.isEmpty()) return Map.of();
		Map<Long, Rating> ratings = new HashMap<>();
		for (TitleRating r : titleRatingRepository.findByUserIdAndTitleIds(userId, titleIds)) {
			ratings.put(r.getTitleId(), r.getRating());
		}
		return ratings;
	}

	// The rating a shared list's taste profile answers to (#273's documented
	// shared-list choice): net sign across the members' personal ratings —
	// up and down from different members cancel back to unrated, so one
	// member's dislike tempers rather than erases another's love. For a
	// personal list this is just the owner's rating.
	public Map<Long, Rating> effectiveRatings(Collection<Long> userIds, Collection<Long> titleIds) {
		if (userIds.isEmpty() || titleIds.isEmpty()) return Map.of();
		Map<Long, Integer> net = new HashMap<>();
		for (TitleRating r : titleRatingRepository.findByUserIdsAndTitleIds(userIds, titleIds)) {
			net.merge(r.getTitleId(), r.getRating() == Rating.UP ? 1 : -1, Integer::sum);
		}
		Map<Long, Rating> effective = new HashMap<>();
		net.forEach((titleId, sum) -> {
			if (sum != 0) effective.put(titleId, sum > 0 ? Rating.UP : Rating.DOWN);
		});
		return effective;
	}

	// Everything these users thumbed down, as TMDB external ids, for the
	// suggestion dedup set (#322). Deliberately a veto and not a net, unlike
	// effectiveRatings above: scoring a shared list blends the members' opinions,
	// but *showing* a title one of them has rejected is a different question, and
	// there the answer is no — the same rule dismissals (#268) already follow.
	// Ratings are user-scoped, so this reaches titles rated on any of the user's
	// lists, not just the one being computed.
	public List<String> downRatedExternalIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) return List.of();
		return titleRatingRepository.findDownRatedExternalIds(userIds);
	}
}
