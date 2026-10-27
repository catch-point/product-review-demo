package ca.leighnet.reviews.storage.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;

/**
 * The preconditions of a PUT or DELETE (RFC 9110 section 13): If-Match, or
 * failing that If-Unmodified-Since. Services check them against the target's
 * current validators inside the write's transaction, after locking its row,
 * so no other write can come between the check and the change.
 *
 * <p>Every ETag here is weak, and If-Match normally compares strongly, which a
 * weak tag never passes. These tags identify versions of the resource, not
 * bytes (they change whenever its dateModified, or anything it shows, does), so
 * If-Match compares them weakly instead.
 *
 * @param ifMatch the entity tags of If-Match, which may be {@code *}
 * @param ifUnmodifiedSince If-Unmodified-Since, used only without If-Match
 * @param required whether a request without either fails with 428
 */
public record Preconditions(List<ETag> ifMatch, Instant ifUnmodifiedSince, boolean required) {

	/** No preconditions, and none required: for writes made by the service itself. */
	public static final Preconditions NONE = new Preconditions(List.of(), null, false);

	public Preconditions {
		ifMatch = List.copyOf(ifMatch);
	}

	public static Preconditions from(HttpHeaders headers, boolean required) {
		List<ETag> ifMatch = new ArrayList<>();
		for (String value : headers.getOrEmpty(HttpHeaders.IF_MATCH)) {
			List<ETag> tags = ETag.parse(value);
			if (tags.isEmpty()) {
				throw ApiException.badRequest("If-Match is not a list of entity tags: " + value);
			}
			ifMatch.addAll(tags);
		}
		return new Preconditions(ifMatch, ifUnmodifiedSince(headers), required);
	}

	/**
	 * Fails with 412 unless the request's preconditions hold for the current
	 * representation, or with 428 when they are required but missing. Call it
	 * after the checks that answer 404 or 403, which take precedence.
	 */
	public void check(Versioned<?> current) {
		if (!ifMatch.isEmpty()) {
			var tag = ETag.create(current.eTag());
			if (ifMatch.stream().noneMatch(t -> t.isWildcard() || t.compare(tag, false))) {
				throw ApiException.preconditionFailed(
						"The resource has changed: its ETag is no longer any of If-Match. Read it again and reapply the change.");
			}
		}
		else if (ifUnmodifiedSince != null) {
			// HTTP dates have whole seconds, as Last-Modified did.
			if (current.lastModified().truncatedTo(ChronoUnit.SECONDS).isAfter(ifUnmodifiedSince)) {
				throw ApiException.preconditionFailed("The resource has been modified since If-Unmodified-Since");
			}
		}
		else if (required) {
			throw ApiException.preconditionRequired(
					"Send If-Match with the resource's ETag (from GET), so that a change made since then is not lost");
		}
	}

	// An invalid date is ignored, as RFC 9110 requires.
	private static Instant ifUnmodifiedSince(HttpHeaders headers) {
		try {
			long millis = headers.getIfUnmodifiedSince();
			return (millis >= 0) ? Instant.ofEpochMilli(millis) : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
