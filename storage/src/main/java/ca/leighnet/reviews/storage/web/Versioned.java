package ca.leighnet.reviews.storage.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;

/**
 * A representation with its cache validators. {@code lastModified} is the
 * latest dateCreated/dateModified of everything the representation shows. The
 * weak ETag is that instant in microseconds, hex encoded, followed by any
 * counts (also hex) that tell apart representations a deletion changed
 * without changing a date: {@code W/"6411f2c7a3b5e-2"}.
 */
public record Versioned<T>(T body, Instant lastModified, String eTag) {

	public static <T> Versioned<T> of(T body, Instant lastModified, long... counts) {
		var tag = new StringBuilder(Long.toHexString(ChronoUnit.MICROS.between(Instant.EPOCH, lastModified)));
		for (long count : counts) {
			tag.append('-').append(Long.toHexString(count));
		}
		return new Versioned<>(body, lastModified, "W/\"" + tag + "\"");
	}

	/** The latest of the given times; nulls (never modified, nothing embedded) are skipped. */
	public static Instant latest(OffsetDateTime... times) {
		return Arrays.stream(times)
			.filter(Objects::nonNull)
			.map(OffsetDateTime::toInstant)
			.max(Instant::compareTo)
			.orElseThrow(() -> new IllegalArgumentException("No timestamps"));
	}

}
