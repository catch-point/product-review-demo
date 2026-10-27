package ca.leighnet.reviews.storage.jsonld;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * The JSON-LD context every document uses: context.jsonld, served at
 * {@code /context.jsonld}. It also maps vocabulary IRIs to the terms that name
 * them, so "https://example.com/categories/audio" is written as "Audio".
 */
@Component
public class JsonLdContext {

	public static final String MEDIA_TYPE_VALUE = "application/ld+json";

	public static final MediaType MEDIA_TYPE = MediaType.parseMediaType(MEDIA_TYPE_VALUE);

	public static final String RESOURCE = "/context.jsonld";

	private final byte[] document;

	private final Map<String, String> termsByIri = new HashMap<>();

	private final Map<String, String> irisByTerm = new HashMap<>();

	JsonLdContext(JsonMapper mapper) {
		this.document = read();
		JsonNode context = mapper.readTree(document).path("@context");
		Map<String, String> prefixes = new HashMap<>();
		context.properties().forEach(entry -> {
			JsonNode value = entry.getValue();
			if (value.path("@prefix").asBoolean(false)) {
				prefixes.put(entry.getKey(), value.path("@id").asString());
			}
		});
		context.properties().forEach(entry -> {
			JsonNode value = entry.getValue();
			String id = value.isString() ? value.asString() : value.path("@id").asString(null);
			if (id != null && !id.startsWith("@") && !prefixes.containsKey(entry.getKey())) {
				String iri = expand(id, prefixes);
				termsByIri.putIfAbsent(iri, entry.getKey());
				irisByTerm.put(entry.getKey(), iri);
			}
		});
	}

	public byte[] document() {
		return document.clone();
	}

	/** The term for an IRI, or the IRI itself when the context has none. */
	public String compact(String iri) {
		return termsByIri.getOrDefault(iri, iri);
	}

	/** The IRI a top-level term stands for. */
	public Optional<String> expand(String term) {
		return Optional.ofNullable(irisByTerm.get(term));
	}

	private static String expand(String id, Map<String, String> prefixes) {
		int colon = id.indexOf(':');
		if (colon > 0) {
			String prefix = prefixes.get(id.substring(0, colon));
			if (prefix != null) {
				return prefix + id.substring(colon + 1);
			}
		}
		return id;
	}

	private static byte[] read() {
		try (InputStream in = JsonLdContext.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is not on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
