package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.wewatch.api.exception.ForbiddenException;
import com.wewatch.api.exception.WatchlistMemberAlreadyExistsException;
import com.wewatch.api.model.MemberRole;
import com.wewatch.api.model.Watchlist;
import com.wewatch.api.model.WatchlistMember;
import com.wewatch.api.model.WatchlistMemberId;
import com.wewatch.api.model.WatchlistType;
import com.wewatch.api.repository.WatchlistMemberRepository;
import com.wewatch.api.repository.WatchlistRepository;

class WatchlistServiceTest {

	private WatchlistRepository watchlistRepository;
	private WatchlistMemberRepository watchlistMemberRepository;
	private WatchlistService service;

	@BeforeEach
	void setUp() {
		watchlistRepository = Mockito.mock(WatchlistRepository.class);
		watchlistMemberRepository = Mockito.mock(WatchlistMemberRepository.class);
		service = new WatchlistService(watchlistRepository, watchlistMemberRepository);
	}

	// ─── provisionPersonalWatchlist ───────────────────────────────────────────

	@Test
	void provisionPersonalWatchlistCreatesWatchlistWithPersonalType() {
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
	void provisionPersonalWatchlistCreatesOwnerMembershipWithDefault() {
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
		assertThat(captured.isDefault()).isTrue();
	}

	@Test
	void provisionPersonalWatchlistSetsTimestamps() {
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

	// ─── createShared ─────────────────────────────────────────────────────────

	@Test
	void createSharedCreatesWatchlistWithSharedType() {
		Watchlist savedWatchlist = new Watchlist(2L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());

		when(watchlistRepository.save(any(Watchlist.class))).thenReturn(savedWatchlist);
		when(watchlistMemberRepository.save(any(WatchlistMember.class))).thenAnswer(inv -> inv.getArgument(0));

		service.createShared("Family List", 10L);

		ArgumentCaptor<Watchlist> watchlistCaptor = ArgumentCaptor.forClass(Watchlist.class);
		verify(watchlistRepository).save(watchlistCaptor.capture());
		Watchlist captured = watchlistCaptor.getValue();
		assertThat(captured.getName()).isEqualTo("Family List");
		assertThat(captured.getType()).isEqualTo(WatchlistType.SHARED);
	}

	@Test
	void createSharedCreatesOwnerMembership() {
		Watchlist savedWatchlist = new Watchlist(2L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());

		when(watchlistRepository.save(any(Watchlist.class))).thenReturn(savedWatchlist);
		when(watchlistMemberRepository.save(any(WatchlistMember.class))).thenAnswer(inv -> inv.getArgument(0));

		service.createShared("Family List", 10L);

		ArgumentCaptor<WatchlistMember> memberCaptor = ArgumentCaptor.forClass(WatchlistMember.class);
		verify(watchlistMemberRepository).save(memberCaptor.capture());
		WatchlistMember captured = memberCaptor.getValue();
		assertThat(captured.getId().getWatchlistId()).isEqualTo(2L);
		assertThat(captured.getId().getUserId()).isEqualTo(10L);
		assertThat(captured.getRole()).isEqualTo(MemberRole.OWNER);
	}

	// ─── findById ─────────────────────────────────────────────────────────────

	@Test
	void findByIdThrowsWhenNotFound() {
		when(watchlistRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findById(99L))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessageContaining("99");
	}

	// ─── requireMember ────────────────────────────────────────────────────────

	@Test
	void requireMemberReturnsMemberWhenFound() {
		Watchlist watchlist = new Watchlist(1L, "Test", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId memberId = new WatchlistMemberId(1L, 10L);
		WatchlistMember member = new WatchlistMember(memberId, MemberRole.MEMBER, Instant.now());

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

		WatchlistMember result = service.requireMember(1L, 10L);

		assertThat(result.getRole()).isEqualTo(MemberRole.MEMBER);
	}

	@Test
	void requireMemberThrowsForbiddenWhenNotMember() {
		Watchlist watchlist = new Watchlist(1L, "Test", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId memberId = new WatchlistMemberId(1L, 10L);

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(memberId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requireMember(1L, 10L))
			.isInstanceOf(ForbiddenException.class)
			.hasMessage("Not a member of this watchlist");
	}

	@Test
	void requireMemberThrowsNotFoundWhenWatchlistMissing() {
		when(watchlistRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requireMember(1L, 10L))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessageContaining("1");
	}

	// ─── requireOwner ─────────────────────────────────────────────────────────

	@Test
	void requireOwnerThrowsForbiddenWhenNotOwner() {
		Watchlist watchlist = new Watchlist(1L, "Test", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId memberId = new WatchlistMemberId(1L, 10L);
		WatchlistMember member = new WatchlistMember(memberId, MemberRole.MEMBER, Instant.now());

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

		assertThatThrownBy(() -> service.requireOwner(1L, 10L))
			.isInstanceOf(ForbiddenException.class)
			.hasMessage("Owner role required");
	}

	// ─── delete ───────────────────────────────────────────────────────────────

	@Test
	void deleteThrowsForbiddenForPersonalWatchlist() {
		Watchlist personal = new Watchlist(1L, "My Watchlist", WatchlistType.PERSONAL, Instant.now(), Instant.now());
		WatchlistMemberId memberId = new WatchlistMemberId(1L, 10L);
		WatchlistMember member = new WatchlistMember(memberId, MemberRole.OWNER, Instant.now());

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(personal));
		when(watchlistMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

		assertThatThrownBy(() -> service.delete(1L, 10L))
			.isInstanceOf(ForbiddenException.class)
			.hasMessage("Personal watchlists cannot be deleted");
	}

	// ─── addMember ────────────────────────────────────────────────────────────

	@Test
	void addMemberThrowsConflictWhenAlreadyMember() {
		Watchlist watchlist = new Watchlist(1L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId callerMemberId = new WatchlistMemberId(1L, 10L);
		WatchlistMember callerMember = new WatchlistMember(callerMemberId, MemberRole.OWNER, Instant.now());
		WatchlistMemberId existingMemberId = new WatchlistMemberId(1L, 20L);

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(callerMemberId)).thenReturn(Optional.of(callerMember));
		when(watchlistMemberRepository.existsById(existingMemberId)).thenReturn(true);

		assertThatThrownBy(() -> service.addMember(1L, 20L, 10L))
			.isInstanceOf(WatchlistMemberAlreadyExistsException.class)
			.hasMessageContaining("20")
			.hasMessageContaining("1");
	}

	// ─── removeMember ─────────────────────────────────────────────────────────

	// ─── setDefault ──────────────────────────────────────────────────────────

	@Test
	void setDefaultClearsPreviousThenSetsNew() {
		Watchlist watchlist = new Watchlist(2L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId memberId = new WatchlistMemberId(2L, 10L);
		WatchlistMember member = new WatchlistMember(memberId, MemberRole.MEMBER, Instant.now());

		when(watchlistRepository.findById(2L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

		service.setDefault(2L, 10L);

		InOrder order = inOrder(watchlistMemberRepository);
		order.verify(watchlistMemberRepository).clearDefault(10L);
		order.verify(watchlistMemberRepository).setDefault(2L, 10L);
	}

	@Test
	void setDefaultThrowsForbiddenWhenNotMember() {
		Watchlist watchlist = new Watchlist(2L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());

		when(watchlistRepository.findById(2L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(new WatchlistMemberId(2L, 10L))).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.setDefault(2L, 10L))
			.isInstanceOf(ForbiddenException.class)
			.hasMessage("Not a member of this watchlist");
	}

	@Test
	void setDefaultThrowsNotFoundWhenWatchlistMissing() {
		when(watchlistRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.setDefault(99L, 10L))
			.isInstanceOf(NoSuchElementException.class)
			.hasMessageContaining("99");
	}

	// ─── removeMember ─────────────────────────────────────────────────────────

	@Test
	void removeMemberThrowsForbiddenWhenTargetIsOwner() {
		Watchlist watchlist = new Watchlist(1L, "Family List", WatchlistType.SHARED, Instant.now(), Instant.now());
		WatchlistMemberId callerMemberId = new WatchlistMemberId(1L, 10L);
		WatchlistMember callerMember = new WatchlistMember(callerMemberId, MemberRole.OWNER, Instant.now());
		WatchlistMemberId targetMemberId = new WatchlistMemberId(1L, 20L);
		WatchlistMember targetMember = new WatchlistMember(targetMemberId, MemberRole.OWNER, Instant.now());

		when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));
		when(watchlistMemberRepository.findById(callerMemberId)).thenReturn(Optional.of(callerMember));
		when(watchlistMemberRepository.findById(targetMemberId)).thenReturn(Optional.of(targetMember));

		assertThatThrownBy(() -> service.removeMember(1L, 20L, 10L))
			.isInstanceOf(ForbiddenException.class)
			.hasMessage("Cannot remove the owner of a watchlist");
	}
}
