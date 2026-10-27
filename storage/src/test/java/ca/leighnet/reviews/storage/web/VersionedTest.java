package ca.leighnet.reviews.storage.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class VersionedTest {

	private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 10, 19, 13, 0, 0, 123_456_000,
			ZoneOffset.UTC);

	@Test
	void weakETagIsTheHexMicrosecondsOfTheTime() {
		long micros = CREATED.toEpochSecond() * 1_000_000 + 123_456;

		var versioned = Versioned.of("body", CREATED.toInstant());

		assertThat(versioned.eTag()).isEqualTo("W/\"" + Long.toHexString(micros) + "\"");
		assertThat(versioned.lastModified()).isEqualTo(CREATED.toInstant());
		assertThat(versioned.body()).isEqualTo("body");
	}

	@Test
	void countsTellApartRepresentationsWithTheSameTime() {
		String hex = Long.toHexString(CREATED.toEpochSecond() * 1_000_000 + 123_456);

		assertThat(Versioned.of("body", CREATED.toInstant(), 2, 31).eTag()).isEqualTo("W/\"" + hex + "-2-1f\"");
		assertThat(Versioned.of("body", CREATED.toInstant(), 1).eTag())
			.isNotEqualTo(Versioned.of("body", CREATED.toInstant(), 2).eTag());
	}

	@Test
	void changesWithEveryMicrosecond() {
		Instant later = CREATED.toInstant().plusNanos(1_000);

		assertThat(Versioned.of("body", later).eTag()).isNotEqualTo(Versioned.of("body", CREATED.toInstant()).eTag());
	}

	@Test
	void latestSkipsMissingTimes() {
		OffsetDateTime modified = CREATED.plusMinutes(5);

		assertThat(Versioned.latest(CREATED, null, modified, null)).isEqualTo(modified.toInstant());
		assertThat(Versioned.latest(CREATED, null)).isEqualTo(CREATED.toInstant());
		assertThatIllegalArgumentException().isThrownBy(() -> Versioned.latest(null, null));
	}

}
