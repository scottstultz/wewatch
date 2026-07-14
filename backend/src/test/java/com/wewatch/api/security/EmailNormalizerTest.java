package com.wewatch.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

	@Test
	void lowercasesAndTrims() {
		assertThat(EmailNormalizer.normalize("  Foo@Example.COM  ")).isEqualTo("foo@example.com");
	}

	@Test
	void leavesCanonicalFormUnchanged() {
		assertThat(EmailNormalizer.normalize("foo@example.com")).isEqualTo("foo@example.com");
	}

	@Test
	void passesNullThrough() {
		assertThat(EmailNormalizer.normalize(null)).isNull();
	}

	/**
	 * Locale.ROOT is load-bearing, and nothing else in the suite would catch its removal: under a
	 * Turkish default locale the platform-default toLowerCase() maps 'I' to the dotless 'ı', so an
	 * address would key to a different string on a Turkish-locale JVM than on the developer's.
	 */
	@Test
	void isIndependentOfTheDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertThat(EmailNormalizer.normalize("INBOX@EXAMPLE.COM")).isEqualTo("inbox@example.com");
		}
		finally {
			Locale.setDefault(original);
		}
	}
}
