package com.wewatch.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wewatch.api.dto.WatchProviderResponse;
import com.wewatch.api.dto.WatchRegionResponse;
import com.wewatch.api.model.TitleType;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbWatchProvider;
import com.wewatch.api.tmdb.TmdbWatchRegion;

@ExtendWith(MockitoExtension.class)
class WatchProviderServiceTest {

	@Mock private TmdbClient tmdbClient;

	private WatchProviderService service() {
		return new WatchProviderService(tmdbClient, 24L);
	}

	@Test
	void mergesMovieAndTvCatalogsKeepingTheBestDisplayPriority() {
		when(tmdbClient.getWatchProviders(TitleType.MOVIE, "US")).thenReturn(List.of(
			new TmdbWatchProvider(8, "Netflix", "/n.jpg", 5),
			new TmdbWatchProvider(337, "Disney Plus", "/d.jpg", 2)));
		when(tmdbClient.getWatchProviders(TitleType.TV, "US")).thenReturn(List.of(
			new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0),
			new TmdbWatchProvider(9, "Prime Video", "/p.jpg", 1)));

		List<WatchProviderResponse> providers = service().providersForRegion("US");

		// One entry per provider, sorted by the lower of its two priorities
		assertThat(providers).extracting(WatchProviderResponse::id).containsExactly(8, 9, 337);
		assertThat(providers.get(0).displayPriority()).isEqualTo(0);
		assertThat(providers.get(0).logoUrl()).isEqualTo("https://image.tmdb.org/t/p/w92/n.jpg");
	}

	@Test
	void providerCatalogIsCachedPerRegion() {
		when(tmdbClient.getWatchProviders(any(), anyString())).thenReturn(List.of(
			new TmdbWatchProvider(8, "Netflix", "/n.jpg", 0)));

		WatchProviderService service = service();
		service.providersForRegion("US");
		service.providersForRegion("us"); // case-normalized to the same entry
		service.providersForRegion("GB");

		// Two calls (movie + tv) per distinct region, not per request
		verify(tmdbClient, times(2)).getWatchProviders(any(), org.mockito.ArgumentMatchers.eq("US"));
		verify(tmdbClient, times(2)).getWatchProviders(any(), org.mockito.ArgumentMatchers.eq("GB"));
	}

	@Test
	void regionsAreSortedByNameAndCached() {
		when(tmdbClient.getWatchRegions()).thenReturn(List.of(
			new TmdbWatchRegion("US", "United States"),
			new TmdbWatchRegion("CA", "Canada")));

		WatchProviderService service = service();
		List<WatchRegionResponse> regions = service.regions();
		service.regions();

		assertThat(regions).extracting(WatchRegionResponse::code).containsExactly("CA", "US");
		verify(tmdbClient, times(1)).getWatchRegions();
	}
}
