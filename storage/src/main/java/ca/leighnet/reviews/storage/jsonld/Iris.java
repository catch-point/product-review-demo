package ca.leighnet.reviews.storage.jsonld;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Node identifiers. Every resource is named by its URL on this server
 * ({@code http://host/products/1}), relative to the current request.
 */
@Component
public class Iris {

	public static final String PEOPLE = "people";

	public static final String PRODUCTS = "products";

	public static final String REVIEWS = "reviews";

	public static final String COMMENTS = "comments";

	private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

	/** Where uploaded images are, from the server's root (ImageController). */
	private static final String IMAGES = "/images/";

	/** An image's name: the SHA-256 of its content and its format's extension (ImageStore). */
	private static final Pattern IMAGE = Pattern.compile("[0-9a-f]{64}\\.[a-z]+");

	public String base() {
		return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
	}

	public String context() {
		return base() + "/context.jsonld";
	}

	public String of(String collection, long id) {
		return base() + "/" + collection + "/" + id;
	}

	public String collection(String path) {
		return base() + path;
	}

	/**
	 * The id named by an IRI of this server, absolute or relative to it.
	 */
	public long id(String collection, String iri, String property) {
		String prefix = base() + "/" + collection + "/";
		String resolved;
		try {
			resolved = URI.create(base() + "/").resolve(iri).toString();
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest(property + " is not a valid IRI: " + iri);
		}
		if (resolved.startsWith(prefix)) {
			try {
				return Long.parseLong(resolved.substring(prefix.length()));
			}
			catch (NumberFormatException ignored) {
				// reported below
			}
		}
		throw ApiException.badRequest(property + " must be a " + prefix + "{id} IRI: " + iri);
	}

	/**
	 * The stored form of an image of this server ({@code POST /images}): its
	 * path from the server's root, {@code /images/<sha256>.<ext>}, without the
	 * scheme, host, port or any prefix a proxy adds, so it names the image
	 * wherever the service is reached from. Given as the image's IRI (absolute,
	 * or relative to this server), or as that path.
	 */
	public String imagePath(String value, String property) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String path = value.startsWith(IMAGES) ? value : null;
		if (path == null) {
			try {
				String resolved = URI.create(base() + "/").resolve(value).toString();
				if (resolved.startsWith(base() + IMAGES)) {
					path = resolved.substring(base().length());
				}
			}
			catch (IllegalArgumentException ignored) {
				// reported below
			}
		}
		if (path == null || !IMAGE.matcher(path.substring(IMAGES.length())).matches()) {
			throw ApiException.badRequest(property + " must be an image uploaded with POST /images (its id, a "
					+ base() + IMAGES + "{sha256}.{ext} IRI): " + value);
		}
		return path;
	}

	/** The IRI of a stored image path ({@link #imagePath}), relative to the current request. */
	public String image(String path) {
		return (path == null) ? null : base() + path;
	}

	/**
	 * Accepts the context documents are written in: none, or this server's.
	 */
	public void checkContext(Object context) {
		if (context == null || context().equals(context) || List.of(context()).equals(context)) {
			return;
		}
		throw ApiException.badRequest("Unsupported @context; omit it or use \"" + context() + "\"");
	}

	/** Accepts a node whose "type" is absent or the expected term. */
	public static void checkType(String type, String expected) {
		if (type != null && !type.equals(expected)) {
			throw ApiException.badRequest("Expected type " + expected + " but was " + type);
		}
	}

	/**
	 * An absolute http or https URL with a host. Anything else, including
	 * {@code javascript:} and {@code data:}, is rejected: these values (such as
	 * a product's url) are written into links. Images are this server's
	 * uploads instead ({@link #imagePath}).
	 */
	public static String absoluteUri(String value, String property) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			if (scheme != null && WEB_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) && uri.getHost() != null) {
				return value;
			}
		}
		catch (IllegalArgumentException ignored) {
			// reported below
		}
		throw ApiException.badRequest(property + " must be an absolute http or https URL: " + value);
	}

}
