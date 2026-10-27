package ca.leighnet.reviews.storage.category;

import java.util.List;

import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.web.HttpCaching;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CategoryController {

	/** A "CategoryList" (schema:DefinedTermSet) node. */
	@JsonPropertyOrder({ "@context", "id", "type" })
	record CategoryListDoc(@JsonProperty("@context") String context, String id, String type,
			List<String> hasDefinedTerm) {
	}

	private final CategoryService categories;

	private final Iris iris;

	CategoryController(CategoryService categories, Iris iris) {
		this.categories = categories;
		this.iris = iris;
	}

	@GetMapping(path = "/categories", produces = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<CategoryListDoc> list() {
		return HttpCaching.shared(
				new CategoryListDoc(iris.context(), iris.collection("/categories"), "CategoryList", categories.terms()));
	}

}
