package ca.leighnet.reviews.storage.web;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Errors the API reports as {@code application/problem+json}.
 */
public class ApiException extends ResponseStatusException {

	private final HttpHeaders headers = new HttpHeaders();

	private ApiException(HttpStatus status, String detail) {
		super(status, detail);
		getBody().setDetail(detail);
	}

	private ApiException retryAfter(Duration wait) {
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000)));
		return this;
	}

	@Override
	public HttpHeaders getHeaders() {
		return headers;
	}

	public static ApiException badRequest(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, detail);
	}

	public static ApiException forbidden(String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, detail);
	}

	public static ApiException notFound(String detail) {
		return new ApiException(HttpStatus.NOT_FOUND, detail);
	}

	public static ApiException conflict(String detail) {
		return new ApiException(HttpStatus.CONFLICT, detail);
	}

	public static ApiException preconditionFailed(String detail) {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, detail);
	}

	public static ApiException preconditionRequired(String detail) {
		return new ApiException(HttpStatus.PRECONDITION_REQUIRED, detail);
	}

	public static ApiException payloadTooLarge(String detail) {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, detail);
	}

	public static ApiException unsupportedMediaType(String detail) {
		return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail);
	}

	/** 429, with Retry-After: the seconds until the request would be allowed. */
	public static ApiException tooManyRequests(String detail, Duration retryAfter) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail).retryAfter(retryAfter);
	}

	/** 503, with Retry-After: the service is too busy for the request now. */
	public static ApiException serviceUnavailable(String detail, Duration retryAfter) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail).retryAfter(retryAfter);
	}

}
