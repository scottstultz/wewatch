package com.wewatch.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wewatch.api.repository.SuggestionDismissalRepository;

// Explicit "Not interested" dismissals (#268). Distinct from the impression
// recency penalty (#264): a dismissal is deliberate user intent, has no window,
// and excludes the title from every shelf forever until the user undoes it.
@Service
public class SuggestionDismissalService {

	private final SuggestionDismissalRepository repository;
	private final Clock clock;

	public SuggestionDismissalService(SuggestionDismissalRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public void dismiss(Long userId, String tmdbId) {
		repository.insertDismissal(userId, tmdbId, Instant.now(clock));
	}

	@Transactional
	public void undismiss(Long userId, String tmdbId) {
		repository.deleteByUserIdAndTmdbId(userId, tmdbId);
	}

	// Union over the given users (#247's scoping rule): a shared list's shelves
	// exclude anything any member has dismissed
	public Set<String> dismissedTmdbIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) return Set.of();
		return new HashSet<>(repository.findTmdbIdsByUserIds(userIds));
	}
}
