package ca.leighnet.reviews.storage.web;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Responses that clients may cache and revalidate. They carry a weak ETag and
 * Last-Modified, and {@code Cache-Control: no-cache}, so a cached copy is
 * always revalidated: If-None-Match or If-Modified-Since then gets 304 Not
 * Modified while nothing changed (Spring evaluates the preconditions).
 */
public final class HttpCaching {

	private HttpCaching() {
	}

	/** A representation that is the same for every caller. */
	public static <T> ResponseEntity<T> ok(Versioned<T> versioned) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			.eTag(versioned.eTag())
			.lastModified(versioned.lastModified())
			.body(versioned.body());
	}

	/**
	 * A representation that is the same for every caller, without validators
	 * of its own (a page of a collection): any cache may keep it, and must
	 * revalidate it. A response that says nothing gets Spring Security's
	 * {@code no-store}, which the edge takes to mean it is personal and never
	 * keeps (edge/default.conf.template): so each shared response says so.
	 */
	public static <T> ResponseEntity<T> shared(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
	}

	/**
	 * A representation that depends on who asks (/people/me, the caller with
	 * their email): only the caller's own cache may keep it.
	 */
	public static <T> ResponseEntity<T> okPrivate(Versioned<T> versioned) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().cachePrivate())
			.varyBy(HttpHeaders.AUTHORIZATION)
			.eTag(versioned.eTag())
			.lastModified(versioned.lastModified())
			.body(versioned.body());
	}

	/**
	 * 201 Created for a POST. Content-Location, the same as Location, says the
	 * body is the new resource's representation, which the validators then
	 * describe: a client can send them back as If-Match without reading it again.
	 */
	public static <T> ResponseEntity<T> created(Versioned<T> versioned, String id) {
		URI location = URI.create(id);
		return ResponseEntity.created(location)
			.header(HttpHeaders.CONTENT_LOCATION, location.toString())
			.eTag(versioned.eTag())
			.lastModified(versioned.lastModified())
			.body(versioned.body());
	}

	/**
	 * 200 OK for a PUT, with the resource as stored. RFC 9110 lets a PUT
	 * response carry validators only when the content was stored unchanged,
	 * which ours seldom is (dateModified, aggregate ratings...). Content-Location
	 * names the target, so the body is its current representation and the
	 * validators describe that body rather than the request's.
	 */
	public static <T> ResponseEntity<T> updated(Versioned<T> versioned, String id) {
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_LOCATION, id)
			.eTag(versioned.eTag())
			.lastModified(versioned.lastModified())
			.body(versioned.body());
	}

}
