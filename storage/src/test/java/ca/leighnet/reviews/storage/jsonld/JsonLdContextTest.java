package ca.leighnet.reviews.storage.jsonld;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLdContextTest {

	private final JsonLdContext context = new JsonLdContext(JsonMapper.builder().build());

	@Test
	void compactsCategoryIrisToTheirTerms() {
		assertThat(context.compact("https://example.com/categories/audio")).isEqualTo("Audio");
		assertThat(context.compact("https://example.com/categories/home")).isEqualTo("Home");
	}

	@Test
	void leavesIrisWithoutATermAlone() {
		assertThat(context.compact("https://example.com/categories/garden")).isEqualTo("https://example.com/categories/garden");
	}

	@Test
	void expandsTermsAndIgnoresKeywordAliasesAndPrefixes() {
		assertThat(context.expand("Books")).hasValue("https://example.com/categories/books");
		assertThat(context.expand("Product")).hasValue("https://schema.org/Product");
		assertThat(context.expand("id")).isEmpty();
		assertThat(context.expand("schema")).isEmpty();
	}

	@Test
	void servesTheDatasetContext() {
		assertThat(new String(context.document())).contains("\"@context\"").contains("schema:Product");
	}

}
