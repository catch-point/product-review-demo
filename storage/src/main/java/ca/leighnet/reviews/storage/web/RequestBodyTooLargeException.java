package ca.leighnet.reviews.storage.web;

import java.io.IOException;

import org.springframework.util.unit.DataSize;

/**
 * A request body without a Content-Length ran past the limit while it was read
 * ({@link RequestBodyLimit}); {@link ApiExceptionHandler} answers 413.
 */
class RequestBodyTooLargeException extends IOException {

	private final DataSize limit;

	RequestBodyTooLargeException(DataSize limit) {
		super("The request body is larger than " + limit.toKilobytes() + " KB");
		this.limit = limit;
	}

	DataSize limit() {
		return limit;
	}

}
