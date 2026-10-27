package ca.leighnet.reviews.storage.web;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 problem details.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler
	ProblemDetail duplicate(DuplicateKeyException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The resource already exists");
	}

	/**
	 * The database is too busy: no connection came free in time
	 * (spring.datasource.hikari.connection-timeout), or a statement ran past
	 * spring.jdbc.template.query-timeout.
	 */
	@ExceptionHandler({ CannotGetJdbcConnectionException.class, QueryTimeoutException.class })
	ResponseEntity<Object> busy(Exception ex, WebRequest request) {
		log.warn("Database busy: {}", ex.getMessage());
		var busy = ApiException.serviceUnavailable("The service is busy; try again shortly", Duration.ofSeconds(5));
		return handleExceptionInternal(busy, busy.getBody(), busy.getHeaders(), busy.getStatusCode(), request);
	}

	/**
	 * The image bucket (image.ImageStore) failed or did not answer in time: only
	 * images are affected, so say so, and when to try again.
	 */
	@ExceptionHandler
	ResponseEntity<Object> imageStore(SdkException ex, WebRequest request) {
		log.warn("Image store unavailable: {}", ex.getMessage());
		var unavailable = ApiException.serviceUnavailable("Images are unavailable; try again shortly",
				Duration.ofSeconds(5));
		return handleExceptionInternal(unavailable, unavailable.getBody(), unavailable.getHeaders(),
				unavailable.getStatusCode(), request);
	}

	@ExceptionHandler
	ProblemDetail integrity(DataIntegrityViolationException ex) {
		log.debug("Constraint violation", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The change conflicts with related data (for example, a person who still has reviews)");
	}

	/** A body that ran past its limit while it was read ({@link RequestBodyLimit}) is 413, not 400. */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof RequestBodyTooLargeException tooLarge) {
				var problem = RequestBodyLimit.tooLarge(tooLarge.limit());
				return handleExceptionInternal(problem, problem.getBody(), headers, problem.getStatusCode(), request);
			}
		}
		return super.handleHttpMessageNotReadable(ex, headers, status, request);
	}

}
