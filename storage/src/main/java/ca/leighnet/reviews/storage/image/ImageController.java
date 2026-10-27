package ca.leighnet.reviews.storage.image;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ImageController {

	private static final String CACHE_CONTROL = CacheControl.maxAge(Duration.ofDays(365))
		.cachePublic()
		.immutable()
		.getHeaderValue();

	private final ImageService images;

	ImageController(ImageService images) {
		this.images = images;
	}

	/** Uploads an image: 201 when new, 200 when identical content was already stored. */
	@PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<ImageDoc> upload(@RequestParam("file") MultipartFile file) {
		var upload = images.upload(file);
		return ResponseEntity.status(upload.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.location(URI.create(upload.image().id()))
			.body(upload.image());
	}

	/**
	 * The image's bytes, streamed from the bucket. Content never changes, so it
	 * may be cached forever, and its ETag is the SHA-256 in its id: a client
	 * that has it gets 304 without the bucket being asked (but If-None-Match: *
	 * asks it, as only an image that exists matches). HEAD asks the bucket
	 * for the image's size only. Ranges are not served: images are small, and
	 * the edge keeps them whole.
	 */
	@GetMapping("/images/{imageId}")
	void get(@PathVariable String imageId, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		var format = ImageStore.parse(imageId).orElseThrow(() -> ApiException.notFound("No image " + imageId));
		String etag = "\"" + imageId.substring(0, imageId.indexOf('.')) + "\"";
		var tags = tags(request.getHeader(HttpHeaders.IF_NONE_MATCH));
		// "*" matches any image that exists, so the bucket is asked: a missing
		// image is 404.
		if (tags.contains("*")) {
			images.find(imageId);
		}
		if (tags.contains(etag) || tags.contains("*")) {
			headers(response, imageId, etag);
			response.setStatus(HttpStatus.NOT_MODIFIED.value());
			return;
		}
		if ("HEAD".equals(request.getMethod())) {
			var image = images.find(imageId);
			headers(response, imageId, etag);
			response.setContentType(format.mediaType());
			response.setContentLengthLong(image.length());
			return;
		}
		try (var image = images.open(imageId)) {
			headers(response, imageId, etag);
			response.setContentType(format.mediaType());
			response.setContentLengthLong(image.image().length());
			image.content().transferTo(response.getOutputStream());
		}
	}

	/** Set only once the image is known to exist, so a 404 is never cached for a year. */
	private static void headers(HttpServletResponse response, String imageId, String etag) {
		response.setHeader(HttpHeaders.ETAG, etag);
		response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.inline().filename(imageId).build().toString());
		response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
	}

	/** If-None-Match's entity tags, for weak comparison (RFC 9110): without W/. */
	private static List<String> tags(String ifNoneMatch) {
		if (ifNoneMatch == null) {
			return List.of();
		}
		return Arrays.stream(ifNoneMatch.split(","))
			.map(String::strip)
			.map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
			.toList();
	}

}
