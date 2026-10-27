package ca.leighnet.reviews.storage.person;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Size;

/**
 * A "Person" node. On input only {@code name} is read; the email comes from the
 * caller's token, and "owns" follows from the products' owner. Every field
 * is bounded all the same, so no input can be larger than its output.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "@context", "id", "type" })
public record PersonDoc(@JsonProperty("@context") Object context, @Size(max = 2000) String id,
		@Size(max = 100) String type, @Size(max = 200) String name, @Size(max = 254) String email,
		@Size(max = 1000) List<@Size(max = 2000) String> owns, OffsetDateTime dateCreated,
		OffsetDateTime dateModified) {

	public static final String TYPE = "Person";

}
