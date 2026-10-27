package ca.leighnet.reviews.storage.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.jsonld.Page;
import ca.leighnet.reviews.storage.journal.JournalRepository;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.person.PersonRepository;
import ca.leighnet.reviews.storage.person.Role;
import ca.leighnet.reviews.storage.product.ProductRepository;
import ca.leighnet.reviews.storage.web.ApiException;
import ca.leighnet.reviews.storage.web.Preconditions;
import ca.leighnet.reviews.storage.web.Versioned;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

	static final BigDecimal DEFAULT_BEST = BigDecimal.valueOf(5);

	static final BigDecimal DEFAULT_WORST = BigDecimal.ONE;

	// numeric(3,1) in schema.sql
	private static final BigDecimal MAX_RATING = new BigDecimal("99.9");

	private final ReviewRepository reviews;

	private final ProductRepository products;

	private final PersonRepository people;

	private final JournalRepository journal;

	private final CurrentUser currentUser;

	private final Iris iris;

	ReviewService(ReviewRepository reviews, ProductRepository products, PersonRepository people,
			JournalRepository journal, CurrentUser currentUser, Iris iris) {
		this.reviews = reviews;
		this.products = products;
		this.people = people;
		this.journal = journal;
		this.currentUser = currentUser;
		this.iris = iris;
	}

	/** A product's reviews, or only the one an author wrote of it (an IRI of a person). */
	@Transactional(readOnly = true)
	public Page<ReviewDoc> listForProduct(long productId, String author, int limit, int offset) {
		Long authorId = (author == null) ? null : iris.id(Iris.PEOPLE, author, "author");
		if (!products.exists(productId)) {
			throw ApiException.notFound("No product " + productId);
		}
		var rows = reviews.listByProduct(productId, authorId, limit, offset);
		Map<Long, List<ReviewRepository.CommentRow>> comments = reviews
			.commentsOf(rows.stream().map(ReviewRepository.Row::id).toList())
			.stream()
			.collect(Collectors.groupingBy(ReviewRepository.CommentRow::reviewId));
		var authors = listedAuthors(Stream.concat(rows.stream().map(ReviewRepository.Row::author),
				comments.values().stream().flatMap(List::stream).map(ReviewRepository.CommentRow::author)));
		var items = rows.stream()
			.map(row -> toDoc(row, comments.getOrDefault(row.id(), List.of()), authors, null))
			.toList();
		return new Page<>(items, reviews.countByProduct(productId, authorId), limit, offset);
	}

	/**
	 * The review with validators covering it, all of its comments (the count
	 * catches a deleted comment, which leaves no newer date) and the names of
	 * the authors it shows.
	 */
	public Versioned<ReviewDoc> read(long id) {
		return version(find(id));
	}

	private Versioned<ReviewDoc> version(ReviewRepository.Row row) {
		var thread = reviews.thread(row.id());
		var authors = authors(Stream.concat(Stream.of(row.author()),
				thread.comments().stream().map(ReviewRepository.CommentRow::author)));
		return Versioned.of(toDoc(row, thread.comments(), authors, iris.context()),
				latest(authors, row.datePublished(), row.dateModified(), thread.stats().latest()),
				thread.stats().count());
	}

	/**
	 * Locks the review for this transaction, checks that the caller wrote it,
	 * then checks the request's preconditions against it as it is now.
	 */
	private ReviewRepository.Row lock(long id, long me, Preconditions preconditions) {
		if (!reviews.lock(id)) {
			throw notFound(id);
		}
		var row = find(id);
		if (row.author() != me) {
			throw ApiException.forbidden("Only the author of review " + id + " may change it");
		}
		preconditions.check(version(row));
		return row;
	}

	/**
	 * Reviewers (by role) may review any active product they do not own, once
	 * (review_item_author_key): after that they update their review. A
	 * discontinued product takes no new reviews; those it has stay, and their
	 * authors may still change them.
	 */
	@Transactional
	public Versioned<ReviewDoc> create(long productId, ReviewDoc input) {
		iris.checkContext(input.context());
		var product = products.find(productId)
			.orElseThrow(() -> ApiException.notFound("No product " + productId));
		long me = currentUser.requirePersonId();
		currentUser.requireRole(Role.REVIEWER);
		if (product.owner() != null && product.owner() == me) {
			throw ApiException.forbidden("Owners cannot review their own product");
		}
		var rating = rating(input);
		String image = iris.imagePath(input.image(), "image");
		var existing = reviews.findIdByAuthor(productId, me);
		if (existing.isPresent()) {
			var conflict = ApiException.conflict("You have already reviewed product " + productId
					+ "; update your review instead");
			conflict.getBody().setProperty("review", iris.of(Iris.REVIEWS, existing.get()));
			throw conflict;
		}
		journal.actAs(me);
		long id = reviews.insert(productId, me, body(input.reviewBody()), image, rating);
		reviews.refreshAggregateRating(productId);
		return read(id);
	}

	@Transactional
	public Versioned<ReviewDoc> update(long id, ReviewDoc input, Preconditions preconditions) {
		iris.checkContext(input.context());
		find(id);
		long me = currentUser.requirePersonId();
		var row = lock(id, me, preconditions);
		var rating = rating(input);
		journal.actAs(me);
		reviews.update(id, body(input.reviewBody()), iris.imagePath(input.image(), "image"), rating);
		reviews.refreshAggregateRating(row.productId());
		return read(id);
	}

	/** Deletes the review with its comments. */
	@Transactional
	public void delete(long id, Preconditions preconditions) {
		find(id);
		long me = currentUser.requirePersonId();
		var row = lock(id, me, preconditions);
		journal.actAs(me);
		reviews.delete(id);
		reviews.refreshAggregateRating(row.productId());
	}

	/**
	 * Only the product's owner may reply, once (comment_review_author_key):
	 * after that they change or delete their reply, and may reply again once it
	 * is deleted. They may reply to the reviews of a discontinued product.
	 */
	@Transactional
	public Versioned<CommentDoc> addComment(long reviewId, CommentDoc input) {
		currentUser.requireRole(Role.OWNER);
		iris.checkContext(input.context());
		Iris.checkType(input.type(), CommentDoc.TYPE);
		var review = find(reviewId);
		long me = currentUser.requirePersonId();
		Long owner = products.ownerOf(review.productId());
		if (owner == null || owner != me) {
			throw ApiException.forbidden("Only the product's owner may reply to its reviews");
		}
		var existing = reviews.findCommentId(reviewId, me);
		if (existing.isPresent()) {
			var conflict = ApiException.conflict("You have already replied to review " + reviewId
					+ "; change your reply instead");
			conflict.getBody().setProperty("comment", iris.of(Iris.COMMENTS, existing.get()));
			throw conflict;
		}
		journal.actAs(me);
		long id = reviews.insertComment(reviewId, me, input.text().strip());
		return readComment(id);
	}

	public Versioned<CommentDoc> readComment(long id) {
		return version(findComment(id));
	}

	private Versioned<CommentDoc> version(ReviewRepository.CommentRow row) {
		var authors = authors(Stream.of(row.author()));
		return Versioned.of(toDoc(row, authors, iris.context()),
				latest(authors, row.datePublished(), row.dateModified()));
	}

	/**
	 * Locks the comment for this transaction, checks that the caller wrote it,
	 * then checks the request's preconditions against it as it is now.
	 */
	private ReviewRepository.CommentRow lockComment(long id, long me, Preconditions preconditions) {
		if (!reviews.lockComment(id)) {
			throw commentNotFound(id);
		}
		var row = findComment(id);
		if (row.author() != me) {
			throw ApiException.forbidden("Only the author of comment " + id + " may change it");
		}
		preconditions.check(version(row));
		return row;
	}

	@Transactional
	public Versioned<CommentDoc> updateComment(long id, CommentDoc input, Preconditions preconditions) {
		iris.checkContext(input.context());
		Iris.checkType(input.type(), CommentDoc.TYPE);
		findComment(id);
		long me = currentUser.requirePersonId();
		lockComment(id, me, preconditions);
		journal.actAs(me);
		reviews.updateComment(id, input.text().strip());
		return readComment(id);
	}

	@Transactional
	public void deleteComment(long id, Preconditions preconditions) {
		findComment(id);
		long me = currentUser.requirePersonId();
		lockComment(id, me, preconditions);
		journal.actAs(me);
		reviews.deleteComment(id);
	}

	/** Validates the rating against its own scale (default 1-5). */
	static ReviewRepository.Rating rating(ReviewDoc input) {
		Iris.checkType(input.type(), ReviewDoc.TYPE);
		var rating = input.reviewRating();
		if (rating == null || rating.ratingValue() == null) {
			throw ApiException.badRequest("reviewRating.ratingValue is required");
		}
		Iris.checkType(rating.type(), ReviewDoc.Rating.TYPE);
		BigDecimal best = (rating.bestRating() != null) ? rating.bestRating() : DEFAULT_BEST;
		BigDecimal worst = (rating.worstRating() != null) ? rating.worstRating() : DEFAULT_WORST;
		BigDecimal value = rating.ratingValue();
		if (worst.compareTo(best) >= 0) {
			throw ApiException.badRequest("worstRating must be less than bestRating");
		}
		if (value.compareTo(worst) < 0 || value.compareTo(best) > 0) {
			throw ApiException.badRequest("ratingValue must be between " + worst + " and " + best);
		}
		if (best.abs().compareTo(MAX_RATING) > 0 || worst.abs().compareTo(MAX_RATING) > 0) {
			throw ApiException.badRequest("Ratings must be within " + MAX_RATING);
		}
		return new ReviewRepository.Rating(value, best, worst);
	}

	private ReviewRepository.Row find(long id) {
		return reviews.find(id).orElseThrow(() -> notFound(id));
	}

	private ReviewRepository.CommentRow findComment(long id) {
		return reviews.findComment(id).orElseThrow(() -> commentNotFound(id));
	}

	private static ApiException notFound(long id) {
		return ApiException.notFound("No review " + id);
	}

	private static ApiException commentNotFound(long id) {
		return ApiException.notFound("No comment " + id);
	}

	/**
	 * The authors of a single review or comment, each read through the people
	 * cache, so a cached review usually needs no database connection.
	 */
	private Map<Long, PersonRepository.Row> authors(Stream<Long> ids) {
		return ids.distinct()
			.map(people::find)
			.flatMap(Optional::stream)
			.collect(Collectors.toMap(PersonRepository.Row::id, Function.identity()));
	}

	/** The authors of a listing, in one query. */
	private Map<Long, PersonRepository.Row> listedAuthors(Stream<Long> ids) {
		return people.findAll(ids.collect(Collectors.toCollection(LinkedHashSet::new)))
			.stream()
			.collect(Collectors.toMap(PersonRepository.Row::id, Function.identity()));
	}

	/** The latest of the given times and of the authors' changes, which rename them. */
	private static Instant latest(Map<Long, PersonRepository.Row> authors, OffsetDateTime... times) {
		var all = new ArrayList<>(Arrays.asList(times));
		authors.values().forEach(person -> all.add(person.changed()));
		return Versioned.latest(all.toArray(OffsetDateTime[]::new));
	}

	private AuthorDoc author(long id, Map<Long, PersonRepository.Row> authors) {
		var person = authors.get(id);
		return new AuthorDoc(iris.of(Iris.PEOPLE, id), AuthorDoc.TYPE, (person != null) ? person.name() : null);
	}

	private ReviewDoc toDoc(ReviewRepository.Row row, List<ReviewRepository.CommentRow> comments,
			Map<Long, PersonRepository.Row> authors, String context) {
		var r = row.rating();
		return new ReviewDoc(context, iris.of(Iris.REVIEWS, row.id()), ReviewDoc.TYPE,
				iris.of(Iris.PRODUCTS, row.productId()), author(row.author(), authors), row.reviewBody(),
				iris.image(row.image()),
				new ReviewDoc.Rating(ReviewDoc.Rating.TYPE, r.ratingValue(), r.bestRating(), r.worstRating()),
				row.datePublished(), row.dateModified(), comments.stream().map(c -> toDoc(c, authors, null)).toList());
	}

	private CommentDoc toDoc(ReviewRepository.CommentRow row, Map<Long, PersonRepository.Row> authors,
			String context) {
		return new CommentDoc(context, iris.of(Iris.COMMENTS, row.id()), CommentDoc.TYPE, row.text(),
				author(row.author(), authors), row.datePublished(), row.dateModified());
	}

	private static String body(String reviewBody) {
		return (reviewBody == null || reviewBody.isBlank()) ? null : reviewBody.strip();
	}

}
