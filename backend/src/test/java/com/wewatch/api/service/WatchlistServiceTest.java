package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistType;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.repository.WatchlistRepository;

class WatchlistServiceTest {

	@Test
	void provisionPersonalWatchlistCreatesWatchlistWithPersonalType() {
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistMemberRepository watchlistMemberRepository = Mockito.mock(WatchlistMemberRepository.class);
		WatchlistService service = new WatchlistService(watchlistRepository, watchlistMemberRepository);
		Watchlist savedWatchlist = new Watchlist(1L, "Scott's Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		when(watchlistRepository.save(any(Watchlist.class))).thenReturn(savedWatchlist);
		when(watchlistMemberRepository.save(any(WatchlistMember.class))).thenAnswer(inv -> inv.getArgument(0));

		service.provisionPersonalWatchlist(42L, "Scott's Watchlist");

		ArgumentCaptor<Watchlist> watchlistCaptor = ArgumentCaptor.forClass(Watchlist.class);
		verify(watchlistRepository).save(watchlistCaptor.capture());
		Watchlist captured = watchlistCaptor.getValue();
		assertThat(captured.getName()).isEqualTo("Scott's Watchlist");
		assertThat(captured.getType()).isEqualTo(WatchlistType.PERSONAL);
	}

	@Test
	void provisionPersonalWatchlistCreatesOwnerMembership() {
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistMemberRepository watchlistMemberRepository = Mockito.mock(WatchlistMemberRepository.class);
		WatchlistService service = new WatchlistService(watchlistRepository, watchlistMemberRepository);
		Watchlist savedWatchlist = new Watchlist(1L, "Scott's Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		when(watchlistRepository.save(any(Watchlist.class))).thenReturn(savedWatchlist);
		when(watchlistMemberRepository.save(any(WatchlistMember.class))).thenAnswer(inv -> inv.getArgument(0));

		service.provisionPersonalWatchlist(42L, "Scott's Watchlist");

		ArgumentCaptor<WatchlistMember> memberCaptor = ArgumentCaptor.forClass(WatchlistMember.class);
		verify(watchlistMemberRepository).save(memberCaptor.capture());
		WatchlistMember captured = memberCaptor.getValue();
		assertThat(captured.getId().getWatchlistId()).isEqualTo(1L);
		assertThat(captured.getId().getUserId()).isEqualTo(42L);
		assertThat(captured.getRole()).isEqualTo(MemberRole.OWNER);
	}

	@Test
	void provisionPersonalWatchlistSetsTimestamps() {
		WatchlistRepository watchlistRepository = Mockito.mock(WatchlistRepository.class);
		WatchlistMemberRepository watchlistMemberRepository = Mockito.mock(WatchlistMemberRepository.class);
		WatchlistService service = new WatchlistService(watchlistRepository, watchlistMemberRepository);
		Watchlist savedWatchlist = new Watchlist(1L, "My Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());

		when(watchlistRepository.save(any(Watchlist.class))).thenReturn(savedWatchlist);
		when(watchlistMemberRepository.save(any(WatchlistMember.class))).thenAnswer(inv -> inv.getArgument(0));

		service.provisionPersonalWatchlist(42L, "My Watchlist");

		ArgumentCaptor<Watchlist> watchlistCaptor = ArgumentCaptor.forClass(Watchlist.class);
		verify(watchlistRepository).save(watchlistCaptor.capture());
		assertThat(watchlistCaptor.getValue().getCreatedAt()).isNotNull();
		assertThat(watchlistCaptor.getValue().getUpdatedAt()).isNotNull();

		ArgumentCaptor<WatchlistMember> memberCaptor = ArgumentCaptor.forClass(WatchlistMember.class);
		verify(watchlistMemberRepository).save(memberCaptor.capture());
		assertThat(memberCaptor.getValue().getJoinedAt()).isNotNull();
	}
}
