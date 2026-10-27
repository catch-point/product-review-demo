package ca.leighnet.reviews.storage.review;

import ca.leighnet.reviews.storage.jsonld.Hydra;
import ca.leighnet.reviews.storage.jsonld.HydraCollection;
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
@RequestMapping(produces = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
class ReviewController {

	private static final String CONSUMES_JSON_LD = JsonLdContext.MEDIA_TYPE_VALUE;

	private static final String CONSUMES_JSON = MediaType.APPLICATION_JSON_VALUE;

	private final ReviewService reviews;

	private final Hydra hydra;

	ReviewController(ReviewService reviews, Hydra hydra) {
		this.reviews = reviews;
		this.hydra = hydra;
	}

	/** A product's reviews with their comments, newest first, or only an author's. */
	@GetMapping("/products/{productId}/reviews")
	ResponseEntity<HydraCollection<ReviewDoc>> list(@PathVariable long productId, @RequestParam(required = false) String author,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@RequestParam(defaultValue = "0") @Min(0) @Max(Hydra.MAX_OFFSET) int offset) {
		return HttpCaching.shared(hydra.collection(reviews.listForProduct(productId, author, limit, offset),
				hydra.search("/products/" + productId + "/reviews",
						new HydraCollection.Mapping("author", "schema:author"),
						new HydraCollection.Mapping(Hydra.LIMIT, "hydra:limit"),
						new HydraCollection.Mapping(Hydra.OFFSET, "hydra:offset"))));
	}

	@PostMapping(path = "/products/{productId}/reviews", consumes = { CONSUMES_JSON_LD, CONSUMES_JSON })
	ResponseEntity<ReviewDoc> create(@PathVariable long productId, @Valid @RequestBody ReviewDoc review) {
		var created = reviews.create(productId, review);
		return HttpCaching.created(created, created.body().id());
	}

	@GetMapping("/reviews/{id}")
	ResponseEntity<ReviewDoc> get(@PathVariable long id) {
		return HttpCaching.ok(reviews.read(id));
	}

	@PutMapping(path = "/reviews/{id}", consumes = { CONSUMES_JSON_LD, CONSUMES_JSON })
	ResponseEntity<ReviewDoc> update(@PathVariable long id, @Valid @RequestBody ReviewDoc review,
			Preconditions preconditions) {
		var updated = reviews.update(id, review, preconditions);
		return HttpCaching.updated(updated, updated.body().id());
	}

	@DeleteMapping("/reviews/{id}")
	ResponseEntity<Void> delete(@PathVariable long id, Preconditions preconditions) {
		reviews.delete(id, preconditions);
		return ResponseEntity.noContent().build();
	}

	/** The product's owner replies to the review; the reply is embedded in it as its "comment". */
	@PostMapping(path = "/reviews/{reviewId}/comments", consumes = { CONSUMES_JSON_LD, CONSUMES_JSON })
	ResponseEntity<CommentDoc> addComment(@PathVariable long reviewId, @Valid @RequestBody CommentDoc comment) {
		var created = reviews.addComment(reviewId, comment);
		return HttpCaching.created(created, created.body().id());
	}

	@GetMapping("/comments/{id}")
	ResponseEntity<CommentDoc> getComment(@PathVariable long id) {
		return HttpCaching.ok(reviews.readComment(id));
	}

	@PutMapping(path = "/comments/{id}", consumes = { CONSUMES_JSON_LD, CONSUMES_JSON })
	ResponseEntity<CommentDoc> updateComment(@PathVariable long id, @Valid @RequestBody CommentDoc comment,
			Preconditions preconditions) {
		var updated = reviews.updateComment(id, comment, preconditions);
		return HttpCaching.updated(updated, updated.body().id());
	}

	@DeleteMapping("/comments/{id}")
	ResponseEntity<Void> deleteComment(@PathVariable long id, Preconditions preconditions) {
		reviews.deleteComment(id, preconditions);
		return ResponseEntity.noContent().build();
	}

}
