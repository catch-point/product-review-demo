package ca.leighnet.reviews.storage.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Size;

/**
 * The "author" of a review or comment: the Person node with just their name,
 * so a client can show who wrote it without reading each person. Emails are
 * never embedded. On input the author is ignored, and may be this node or the
 * Person's IRI alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "id", "type" })
public record AuthorDoc(@Size(max = 2000) String id, @Size(max = 100) String type, @Size(max = 200) String name) {

	public static final String TYPE = "Person";

	@JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
	public AuthorDoc {
	}

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	static AuthorDoc of(String id) {
		return new AuthorDoc(id, null, null);
	}

}
