package ca.leighnet.reviews.storage.product;

import java.math.BigDecimal;

import ca.leighnet.reviews.storage.jsonld.Hydra;
import ca.leighnet.reviews.storage.jsonld.HydraCollection;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.web.HttpCaching;
import ca.leighnet.reviews.storage.web.Preconditions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/products",
		produces = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
class ProductController {

	private final ProductService products;

	private final Hydra hydra;

	private final Iris iris;

	ProductController(ProductService products, Hydra hydra, Iris iris) {
		this.products = products;
		this.hydra = hydra;
		this.iris = iris;
	}

	/**
	 * Active products, best rated first; filter by category (term or IRI) or a
	 * range of ratings. "seeAlso" links to the categories the filter accepts.
	 */
	@GetMapping
	ResponseEntity<HydraCollection<ProductDoc>> list(@RequestParam(required = false) String category,
			@RequestParam(required = false) BigDecimal minRating,
			@RequestParam(required = false) BigDecimal maxRating,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@RequestParam(defaultValue = "0") @Min(0) @Max(Hydra.MAX_OFFSET) int offset) {
		var filter = new ProductRepository.Filter(null, minRating, maxRating, limit, offset);
		return HttpCaching.shared(hydra.collection(products.list(category, filter),
				hydra.search("/products", new HydraCollection.Mapping("category", "schema:category"),
						new HydraCollection.Mapping("minRating", "schema:ratingValue"),
						new HydraCollection.Mapping("maxRating", "schema:ratingValue"),
						new HydraCollection.Mapping(Hydra.LIMIT, "hydra:limit"),
						new HydraCollection.Mapping(Hydra.OFFSET, "hydra:offset")),
				iris.collection("/categories")));
	}

	@PostMapping(consumes = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<ProductDoc> create(@Valid @RequestBody ProductDoc product) {
		var created = products.create(product);
		return HttpCaching.created(created, created.body().id());
	}

	@GetMapping("/{id}")
	ResponseEntity<ProductDoc> get(@PathVariable long id) {
		return HttpCaching.ok(products.read(id));
	}

	@PutMapping(path = "/{id}", consumes = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<ProductDoc> update(@PathVariable long id, @Valid @RequestBody ProductDoc product,
			Preconditions preconditions) {
		var updated = products.update(id, product, preconditions);
		return HttpCaching.updated(updated, updated.body().id());
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable long id, Preconditions preconditions) {
		products.delete(id, preconditions);
		return ResponseEntity.noContent().build();
	}

}
