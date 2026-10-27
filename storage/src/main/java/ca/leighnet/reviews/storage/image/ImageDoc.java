package ca.leighnet.reviews.storage.image;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * An "ImageObject" (schema:ImageObject) describing an uploaded image. Use its
 * {@code id} (the same as {@code contentUrl}) as a Product's or Review's
 * "image".
 */
@JsonPropertyOrder({ "@context", "id", "type" })
public record ImageDoc(@JsonProperty("@context") String context, String id, String type, String contentUrl,
		String encodingFormat, int width, int height, String sha256, OffsetDateTime uploadDate) {

	public static final String TYPE = "ImageObject";

}
