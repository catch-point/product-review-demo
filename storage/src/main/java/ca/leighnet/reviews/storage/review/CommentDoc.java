package ca.leighnet.reviews.storage.review;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A "Comment" node, listed under its review's "comment", with its author
 * embedded ({@link AuthorDoc}). On input only {@code text} is read.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "@context", "id", "type" })
public record CommentDoc(@JsonProperty("@context") Object context, @Size(max = 2000) String id,
		@Size(max = 100) String type, @NotBlank @Size(max = 5000) String text, @Valid AuthorDoc author, OffsetDateTime datePublished,
		OffsetDateTime dateModified) {

	public static final String TYPE = "Comment";

}
