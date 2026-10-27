package ca.leighnet.reviews.storage.category;

import java.util.Collection;
import java.util.List;

import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.stereotype.Service;

/**
 * Categories are written as context terms ("Audio") where the context defines
 * one, and as category IRIs otherwise.
 */
@Service
public class CategoryService {

	private final CategoryRepository categories;

	private final JsonLdContext context;

	CategoryService(CategoryRepository categories, JsonLdContext context) {
		this.categories = categories;
		this.context = context;
	}

	public List<String> terms() {
		return compact(categories.ids());
	}

	public List<String> compact(Collection<String> iris) {
		return iris.stream().map(context::compact).toList();
	}

	/** The IRI of a known category, or 400. */
	public String resolve(String termOrIri) {
		String candidate = context.expand(termOrIri).orElse(termOrIri);
		return categories.resolve(candidate)
			.orElseThrow(() -> ApiException.badRequest("Unknown category " + termOrIri + "; see /categories"));
	}

}
