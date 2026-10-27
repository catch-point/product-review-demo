package ca.leighnet.reviews.storage.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves this API's OpenAPI document, openapi.yaml.
 */
@RestController
class OpenApiController {

	static final String RESOURCE = "/openapi.yaml";

	private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

	private final byte[] document;

	OpenApiController() {
		try (InputStream in = OpenApiController.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is not on the classpath");
			}
			this.document = in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@GetMapping("/openapi.yml")
	ResponseEntity<byte[]> openApi() {
		return ResponseEntity.ok()
			.contentType(YAML)
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
			.body(document);
	}

}
