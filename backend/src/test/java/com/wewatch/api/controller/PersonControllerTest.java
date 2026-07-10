package com.wewatch.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.wewatch.api.exception.TmdbApiException;
import com.wewatch.api.model.User;
import com.wewatch.api.security.JwtTokenService;
import com.wewatch.api.security.SecurityConfig;
import com.wewatch.api.service.UserService;
import com.wewatch.api.tmdb.TmdbClient;
import com.wewatch.api.tmdb.TmdbPersonCredit;
import com.wewatch.api.tmdb.TmdbPersonCredits;
import com.wewatch.api.tmdb.TmdbPersonDetail;

@WebMvcTest(PersonController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class PersonControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private TmdbClient tmdbClient;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@MockBean
	private JwtTokenService jwtTokenService;

	private static final User TEST_USER = new User(1L, "test@example.com", "Test User", Instant.EPOCH, Instant.EPOCH, "google", "sub-123");

	private static final Jwt TEST_JWT = Jwt.withTokenValue("test-token")
		.header("alg", "HS256")
		.claim("sub", "1")
		.issuer("wewatch")
		.issuedAt(Instant.EPOCH)
		.expiresAt(Instant.EPOCH.plusSeconds(86400))
		.build();

	private static final TmdbPersonCredit MATRIX = new TmdbPersonCredit(603, "movie", "The Matrix",
		null, "A computer hacker learns the true nature of reality.", "1999-03-31", null,
		"/matrix.jpg", List.of(28, 878), 92.5, 24000, "Thomas A. Anderson");

	private static final TmdbPersonCredit SWEDISH_DICKS = new TmdbPersonCredit(1408, "tv", null,
		"Swedish Dicks", "Two men run a private investigation firm.", null, "2016-09-15",
		"/dicks.jpg", List.of(35), 8.1, 40, "Tex");

	// Blank character, talk genre, more popular than any real credit — exactly the
	// shape TMDB returns for late-night appearances
	private static final TmdbPersonCredit LATE_NIGHT = new TmdbPersonCredit(999, "tv", null,
		"Late Night", null, null, "1993-09-13", "/latenight.jpg", List.of(10767, 35), 99.0, 100, "");

	@BeforeEach
	void setupAuth() {
		when(jwtDecoder.decode(any())).thenReturn(TEST_JWT);
		when(userService.findById(1L)).thenReturn(TEST_USER);
	}

	private static TmdbPersonDetail keanu(TmdbPersonCredit... credits) {
		return new TmdbPersonDetail(6384, "Keanu Reeves", "A Canadian actor.", "/keanu.jpg",
			"Acting", "1964-09-02", "Beirut, Lebanon",
			new TmdbPersonCredits(List.of(credits), List.of()));
	}

	@Test
	void getPersonReturnsBioAndCombinedCredits() throws Exception {
		when(tmdbClient.getPersonDetail(6384)).thenReturn(keanu(MATRIX, SWEDISH_DICKS));

		mockMvc.perform(get("/api/people/6384").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(6384))
			.andExpect(jsonPath("$.name").value("Keanu Reeves"))
			.andExpect(jsonPath("$.biography").value("A Canadian actor."))
			.andExpect(jsonPath("$.profileUrl").value("https://image.tmdb.org/t/p/w185/keanu.jpg"))
			.andExpect(jsonPath("$.knownForDepartment").value("Acting"))
			.andExpect(jsonPath("$.birthday").value("1964-09-02"))
			.andExpect(jsonPath("$.placeOfBirth").value("Beirut, Lebanon"))
			.andExpect(jsonPath("$.credits.length()").value(2))
			.andExpect(jsonPath("$.credits[0].externalId").value("603"))
			.andExpect(jsonPath("$.credits[0].type").value("MOVIE"))
			.andExpect(jsonPath("$.credits[0].name").value("The Matrix"))
			.andExpect(jsonPath("$.credits[0].releaseDate").value("1999-03-31"))
			.andExpect(jsonPath("$.credits[0].posterUrl").value("https://image.tmdb.org/t/p/w500/matrix.jpg"))
			.andExpect(jsonPath("$.credits[1].externalId").value("1408"))
			.andExpect(jsonPath("$.credits[1].type").value("TV"))
			.andExpect(jsonPath("$.credits[1].name").value("Swedish Dicks"))
			.andExpect(jsonPath("$.credits[1].releaseDate").value("2016-09-15"));
	}

	@Test
	void getPersonAppliesTheCreditFilters() throws Exception {
		when(tmdbClient.getPersonDetail(6384)).thenReturn(keanu(LATE_NIGHT, MATRIX));

		mockMvc.perform(get("/api/people/6384").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.credits.length()").value(1))
			.andExpect(jsonPath("$.credits[0].name").value("The Matrix"));
	}

	@Test
	void getPersonWithNoCreditsReturnsAnEmptyList() throws Exception {
		when(tmdbClient.getPersonDetail(6384)).thenReturn(keanu());

		mockMvc.perform(get("/api/people/6384").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Keanu Reeves"))
			.andExpect(jsonPath("$.credits").isEmpty());
	}

	@Test
	void getPersonWithoutAHeadshotReturnsANullProfileUrl() throws Exception {
		when(tmdbClient.getPersonDetail(6384)).thenReturn(new TmdbPersonDetail(6384, "Keanu Reeves",
			null, null, "Acting", null, null, null));

		mockMvc.perform(get("/api/people/6384").header("Authorization", "Bearer test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profileUrl").doesNotExist())
			.andExpect(jsonPath("$.birthday").doesNotExist())
			.andExpect(jsonPath("$.credits").isEmpty());
	}

	@Test
	void getPersonRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/people/6384"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(tmdbClient);
	}

	@Test
	void getPersonReturnsBadGatewayWhenTmdbFails() throws Exception {
		when(tmdbClient.getPersonDetail(6384))
			.thenThrow(new TmdbApiException("TMDB get person detail failed", new RuntimeException()));

		mockMvc.perform(get("/api/people/6384").header("Authorization", "Bearer test-token"))
			.andExpect(status().isBadGateway());
	}

}
