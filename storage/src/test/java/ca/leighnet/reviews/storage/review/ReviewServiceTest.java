package ca.leighnet.reviews.storage.review;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.journal.JournalRepository;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.person.PersonRepository;
import ca.leighnet.reviews.storage.person.Role;
import ca.leighnet.reviews.storage.product.ProductRepository;
import ca.leighnet.reviews.storage.web.ApiException;
import ca.leighnet.reviews.storage.web.Preconditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

	private static final String PHOTO_PATH = "/images/" + "a".repeat(64) + ".jpg";

	private static final String PHOTO = "http://localhost" + PHOTO_PATH;

	@Mock
	private ReviewRepository reviews;

	@Mock
	private ProductRepository products;

	@Mock
	private PersonRepository people;

	@Mock
	private JournalRepository journal;

	@Mock
	private CurrentUser currentUser;

	@Mock
	private Iris iris;

	@InjectMocks
	private ReviewService service;

	@BeforeEach
	void setUp() {
		lenient().when(products.find(1)).thenReturn(Optional.of(product(1, 9L)));
		lenient().when(currentUser.requirePersonId()).thenReturn(42L);
	}

	@Test
	void ownersCannotReviewTheirOwnProduct() {
		given(products.find(1)).willReturn(Optional.of(product(1, 42L)));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(1, review(4, null, null)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(reviews, never()).insert(anyLong(), anyLong(), any(), any(), any());
		verifyNoInteractions(journal);
	}

	@Test
	void onlyReviewersByRoleReview() {
		willThrow(ApiException.forbidden("no")).given(currentUser).requireRole(Role.REVIEWER);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(1, review(4, null, null)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(reviews, never()).insert(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void reviewersReviewAProductOnce() {
		given(reviews.findIdByAuthor(1, 42)).willReturn(Optional.of(7L));
		given(iris.of(Iris.REVIEWS, 7)).willReturn("http://localhost/reviews/7");

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(1, review(4, null, null)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
			.satisfies(ex -> assertThat(ex.getBody().getProperties()).containsEntry("review",
					"http://localhost/reviews/7"));
		verify(reviews, never()).insert(anyLong(), anyLong(), any(), any(), any());
		verifyNoInteractions(journal);
	}

	@Test
	void discontinuedProductsTakeNoNewReviews() {
		given(products.find(1)).willReturn(Optional.empty());

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.create(1, review(4, null, null)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		verify(reviews, never()).insert(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void onlyTheAuthorChangesAReview() {
		given(reviews.find(7)).willReturn(Optional.of(row(7, 1, 8)));
		given(reviews.lock(7)).willReturn(true);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.delete(7, Preconditions.NONE))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(reviews, never()).delete(anyLong());
		verifyNoInteractions(journal);
	}

	@Test
	void onlyTheProductsOwnerReplies() {
		// 42 wrote the review of product 1, which 9 owns.
		given(reviews.find(7)).willReturn(Optional.of(row(7, 1, 42)));
		given(products.ownerOf(1)).willReturn(9L);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.addComment(7, comment("Me too")))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
			.withMessageContaining("owner");
		verify(reviews, never()).insertComment(anyLong(), anyLong(), any());
		verifyNoInteractions(journal);
	}

	@Test
	void onlyOwnersByRoleReply() {
		willThrow(ApiException.forbidden("no")).given(currentUser).requireRole(Role.OWNER);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.addComment(7, comment("Me too")))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(reviews, never()).insertComment(anyLong(), anyLong(), any());
	}

	@Test
	void ownersReplyToAReviewOnce() {
		given(products.ownerOf(1)).willReturn(42L);
		given(reviews.find(7)).willReturn(Optional.of(row(7, 1, 8)));
		given(reviews.findCommentId(7, 42)).willReturn(Optional.of(5L));
		given(iris.of(Iris.COMMENTS, 5)).willReturn("http://localhost/comments/5");

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.addComment(7, comment("Again")))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
			.satisfies(ex -> assertThat(ex.getBody().getProperties()).containsEntry("comment",
					"http://localhost/comments/5"));
		verify(reviews, never()).insertComment(anyLong(), anyLong(), any());
		verifyNoInteractions(journal);
	}

	@Test
	void ownersStillReplyOnceTheirProductIsDiscontinued() {
		given(products.ownerOf(1)).willReturn(42L);
		given(reviews.find(7)).willReturn(Optional.of(row(7, 1, 8)));
		given(reviews.insertComment(7, 42, "Thanks for the years")).willReturn(5L);
		given(reviews.findComment(5)).willReturn(Optional.of(new ReviewRepository.CommentRow(5, 7, 42,
				"Thanks for the years", OffsetDateTime.now(), null)));

		service.addComment(7, comment("Thanks for the years"));
		verify(reviews).insertComment(7, 42, "Thanks for the years");
	}

	@Test
	void theProductsOwnerReplies() {
		given(products.ownerOf(1)).willReturn(42L);
		given(reviews.find(7)).willReturn(Optional.of(row(7, 1, 8)));
		given(reviews.insertComment(7, 42, "Thank you")).willReturn(5L);
		given(reviews.findComment(5)).willReturn(Optional.of(new ReviewRepository.CommentRow(5, 7, 42, "Thank you",
				OffsetDateTime.now(), null)));

		assertThat(service.addComment(7, comment(" Thank you ")).body().text()).isEqualTo("Thank you");
		var order = inOrder(journal, reviews);
		order.verify(journal).actAs(42);
		order.verify(reviews).insertComment(7, 42, "Thank you");
	}

	private static CommentDoc comment(String text) {
		return new CommentDoc(null, null, "Comment", text, null, null, null);
	}

	@Test
	void onlyTheAuthorChangesAComment() {
		given(reviews.findComment(5)).willReturn(Optional.of(new ReviewRepository.CommentRow(5, 7, 9, "Thanks",
				OffsetDateTime.now(), null)));
		given(reviews.lockComment(5)).willReturn(true);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.deleteComment(5, Preconditions.NONE))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(reviews, never()).deleteComment(anyLong());
	}

	@Test
	void createsTheReview() {
		given(iris.of(any(), anyLong()))
			.willAnswer(call -> "http://localhost/" + call.getArgument(0) + "/" + call.getArgument(1));
		given(people.find(42)).willReturn(Optional.of(new PersonRepository.Row(42, "Rob", "rob@example.com",
				OffsetDateTime.now(), null)));
		given(iris.imagePath(PHOTO, "image")).willReturn(PHOTO_PATH);
		given(iris.image(PHOTO_PATH)).willReturn(PHOTO);
		given(reviews.insert(1, 42, "Lovely", PHOTO_PATH, new ReviewRepository.Rating(BigDecimal.valueOf(4),
				ReviewService.DEFAULT_BEST, ReviewService.DEFAULT_WORST)))
			.willReturn(7L);
		given(reviews.find(7)).willReturn(Optional.of(new ReviewRepository.Row(7, 1, 42, "Lovely", PHOTO_PATH,
				new ReviewRepository.Rating(BigDecimal.valueOf(4), BigDecimal.valueOf(5), BigDecimal.ONE),
				OffsetDateTime.now(), null)));
		given(reviews.thread(7))
			.willReturn(new ReviewRepository.Thread(List.of(), new ReviewRepository.CommentStats(0, null)));

		var created = service.create(1, new ReviewDoc(null, null, "Review", null, null, "  Lovely ", PHOTO,
				new ReviewDoc.Rating("Rating", BigDecimal.valueOf(4), null, null), null, null, null));

		assertThat(created.body().type()).isEqualTo("Review");
		assertThat(created.body().image()).isEqualTo(PHOTO);
		assertThat(created.body().author())
			.isEqualTo(new AuthorDoc("http://localhost/people/42", "Person", "Rob"));
		var order = inOrder(journal, reviews);
		order.verify(journal).actAs(42);
		order.verify(reviews).insert(anyLong(), anyLong(), any(), any(), any());
		order.verify(reviews).refreshAggregateRating(1);
	}

	@Test
	void imagesMustBeUploads() {
		willThrow(ApiException.badRequest("image must be an image uploaded with POST /images")).given(iris)
			.imagePath("photo.jpg", "image");
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.create(1, new ReviewDoc(null, null, "Review", null, null, null, "photo.jpg",
					new ReviewDoc.Rating(null, BigDecimal.ONE, null, null), null, null, null)))
			.withMessageContaining("image");
		verify(reviews, never()).insert(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void ratingsMustFitTheirScale() {
		assertThat(ReviewService.rating(review(10, 0, 10)).ratingValue()).isEqualByComparingTo("10");
		assertBadRequest(review(6, null, null), "between 1 and 5");
		assertBadRequest(review(0, null, null), "between 1 and 5");
		assertBadRequest(review(3, 5, 5), "worstRating must be less than bestRating");
		assertBadRequest(review(3, 1, 500), "within");
		assertBadRequest(new ReviewDoc(null, null, "Review", null, null, null, null, null, null, null, null), "required");
		assertBadRequest(new ReviewDoc(null, null, "Comment", null, null, null, null,
				new ReviewDoc.Rating(null, BigDecimal.ONE, null, null), null, null, null), "Expected type Review");
	}

	private static void assertBadRequest(ReviewDoc review, String message) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> ReviewService.rating(review))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
			.withMessageContaining(message);
	}

	private static ProductRepository.Row product(long id, Long owner) {
		return new ProductRepository.Row(id,
				new ProductRepository.Fields("Aria Headphones", null, null, null, null, "Aria", "00012345600012",
						"ARIA-WH-01", "WH-1000"),
				owner, List.of(), null, OffsetDateTime.now(), null);
	}

	private static ReviewRepository.Row row(long id, long productId, long author) {
		return new ReviewRepository.Row(id, productId, author, "Lovely", null,
				new ReviewRepository.Rating(BigDecimal.valueOf(4), BigDecimal.valueOf(5), BigDecimal.ONE),
				OffsetDateTime.now(), null);
	}

	private static ReviewDoc review(int value, Integer worst, Integer best) {
		return new ReviewDoc(null, null, null, null, null, null, null,
				new ReviewDoc.Rating(null, BigDecimal.valueOf(value), (best != null) ? BigDecimal.valueOf(best) : null,
						(worst != null) ? BigDecimal.valueOf(worst) : null),
				null, null, null);
	}

}
