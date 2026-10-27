package ca.leighnet.reviews.storage.jsonld;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ContextController {

	private final JsonLdContext context;

	ContextController(JsonLdContext context) {
		this.context = context;
	}

	@GetMapping("/context.jsonld")
	ResponseEntity<byte[]> context() {
		return ResponseEntity.ok()
			.contentType(JsonLdContext.MEDIA_TYPE)
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
			.body(context.document());
	}

}
