package ca.leighnet.reviews.storage.review;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A "Review" node with its "reviewRating" and "comment": the product
 * owner's reply, if any. On input, id, itemReviewed, author, comment and the
 * dates are ignored: the URL and the caller determine them. The author is
 * embedded with their name ({@link AuthorDoc}). IRIs are at most 2000
 * characters.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "@context", "id", "type" })
public record ReviewDoc(@JsonProperty("@context") Object context, @Size(max = 2000) String id,
		@Size(max = 100) String type, @Size(max = 2000) String itemReviewed, @Valid AuthorDoc author,
		@Size(max = 10000) String reviewBody, @Size(max = 2000) String image, @NotNull @Valid Rating reviewRating,
		OffsetDateTime datePublished, OffsetDateTime dateModified, @Size(max = 1) List<CommentDoc> comment) {

	public static final String TYPE = "Review";

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "type" })
	public record Rating(@Size(max = 100) String type, @NotNull BigDecimal ratingValue, BigDecimal bestRating,
			BigDecimal worstRating) {

		public static final String TYPE = "Rating";

	}

}
