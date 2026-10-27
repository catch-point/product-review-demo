package ca.leighnet.reviews.storage.product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A "Product" node. On input, id, owner, aggregateRating, dateCreated,
 * dateModified and seeAlso are ignored: the server assigns them. "seeAlso"
 * links to the product's reviews. IRIs and URLs are at most 2000 characters,
 * and a product belongs to at most 20 categories.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "@context", "id", "type" })
public record ProductDoc(@JsonProperty("@context") Object context, @Size(max = 2000) String id,
		@Size(max = 100) String type, @NotBlank @Size(max = 200) String name, @Size(max = 5000) String description,
		@Size(max = 2000) String image, @Size(max = 2000) String url, @Size(max = 1) List<@Size(max = 2000) String> sameAs,
		@Size(max = 20) List<@Size(max = 2000) String> category, @Size(max = 200) String brand,
		@Pattern(regexp = "^\\d{8}$|^\\d{12,14}$") @Size(max = 14) String gtin, @Size(max = 100) String sku,
		@Size(max = 100) String mpn, @Size(max = 2000) String owner, AggregateRating aggregateRating,
		OffsetDateTime dateCreated, OffsetDateTime dateModified, @Size(max = 2000) String seeAlso) {

	public static final String TYPE = "Product";

	@JsonPropertyOrder({ "type" })
	public record AggregateRating(String type, BigDecimal ratingValue, BigDecimal bestRating,
			BigDecimal worstRating, int ratingCount, int reviewCount) {

		public static final String TYPE = "AggregateRating";

	}

}
