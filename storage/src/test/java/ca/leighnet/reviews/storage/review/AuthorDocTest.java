package ca.leighnet.reviews.storage.review;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorDocTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void writesThePersonNodeWithItsName() {
		assertThat(json.writeValueAsString(new AuthorDoc("http://localhost/people/2", AuthorDoc.TYPE, "Rob")))
			.isEqualTo("{\"id\":\"http://localhost/people/2\",\"type\":\"Person\",\"name\":\"Rob\"}");
	}

	/** Clients may send back the document they read, or an older one with the IRI alone. */
	@Test
	void readsTheNodeOrTheIriAlone() {
		var node = json.readValue("{\"type\":\"Review\",\"author\":{\"id\":\"http://localhost/people/2\","
				+ "\"type\":\"Person\",\"name\":\"Rob\"}}", ReviewDoc.class);
		assertThat(node.author()).isEqualTo(new AuthorDoc("http://localhost/people/2", "Person", "Rob"));

		var iri = json.readValue("{\"type\":\"Comment\",\"text\":\"Hi\",\"author\":\"http://localhost/people/2\"}",
				CommentDoc.class);
		assertThat(iri.author()).isEqualTo(new AuthorDoc("http://localhost/people/2", null, null));
	}

}
