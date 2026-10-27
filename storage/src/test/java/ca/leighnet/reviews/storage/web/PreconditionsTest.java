package ca.leighnet.reviews.storage.web;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PreconditionsTest {

	private static final Instant MODIFIED = Instant.parse("2026-10-19T12:00:00.250Z");

	private static final Versioned<String> CURRENT = Versioned.of("body", MODIFIED, 2);

	@Test
	void ifMatchPassesWithTheCurrentTagWeakOrNot() {
		String opaque = CURRENT.eTag().substring(2);

		assertPasses(headers(HttpHeaders.IF_MATCH, CURRENT.eTag()));
		assertPasses(headers(HttpHeaders.IF_MATCH, opaque));
		assertPasses(headers(HttpHeaders.IF_MATCH, "\"old\", " + CURRENT.eTag()));
		assertPasses(headers(HttpHeaders.IF_MATCH, "*"));
	}

	@Test
	void ifMatchFailsWithAnyOtherTag() {
		assertStatus(headers(HttpHeaders.IF_MATCH, "W/\"0\""), HttpStatus.PRECONDITION_FAILED);
		assertStatus(headers(HttpHeaders.IF_MATCH, Versioned.of("body", MODIFIED, 1).eTag()),
				HttpStatus.PRECONDITION_FAILED);
	}

	@Test
	void ifMatchTakesPrecedenceOverIfUnmodifiedSince() {
		var headers = headers(HttpHeaders.IF_MATCH, CURRENT.eTag());
		headers.setIfUnmodifiedSince(MODIFIED.minusSeconds(60));

		assertPasses(headers);
	}

	@Test
	void ifUnmodifiedSinceComparesWholeSeconds() {
		var headers = new HttpHeaders();
		headers.setIfUnmodifiedSince(Instant.parse("2026-10-19T12:00:00Z"));
		assertPasses(headers);

		headers.setIfUnmodifiedSince(Instant.parse("2026-10-19T11:59:59Z"));
		assertStatus(headers, HttpStatus.PRECONDITION_FAILED);
	}

	@Test
	void anInvalidIfUnmodifiedSinceIsIgnored() {
		assertStatus(headers(HttpHeaders.IF_UNMODIFIED_SINCE, "yesterday"), HttpStatus.PRECONDITION_REQUIRED);
		assertThatNoException()
			.isThrownBy(() -> Preconditions.from(headers(HttpHeaders.IF_UNMODIFIED_SINCE, "yesterday"), false)
				.check(CURRENT));
	}

	@Test
	void missingPreconditionsFailOnlyWhenRequired() {
		assertStatus(new HttpHeaders(), HttpStatus.PRECONDITION_REQUIRED);
		assertThatNoException().isThrownBy(() -> Preconditions.from(new HttpHeaders(), false).check(CURRENT));
		assertThatNoException().isThrownBy(() -> Preconditions.NONE.check(CURRENT));
	}

	@Test
	void malformedIfMatchIsABadRequest() {
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> Preconditions.from(headers(HttpHeaders.IF_MATCH, "unquoted"), true))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	private static HttpHeaders headers(String name, String value) {
		var headers = new HttpHeaders();
		headers.add(name, value);
		return headers;
	}

	private static void assertPasses(HttpHeaders headers) {
		assertThatNoException().isThrownBy(() -> Preconditions.from(headers, true).check(CURRENT));
	}

	private static void assertStatus(HttpHeaders headers, HttpStatus status) {
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> Preconditions.from(headers, true).check(CURRENT))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(status));
	}

}
