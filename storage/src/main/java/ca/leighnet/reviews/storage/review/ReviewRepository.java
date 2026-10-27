package ca.leighnet.reviews.storage.review;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ca.leighnet.reviews.storage.cache.Caches;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

	public record Rating(BigDecimal ratingValue, BigDecimal bestRating, BigDecimal worstRating) {

		Map<String, Object> params() {
			return Map.of("ratingValue", ratingValue, "bestRating", bestRating, "worstRating", worstRating);
		}

	}

	public record Row(long id, long productId, long author, String reviewBody, String image, Rating rating,
			OffsetDateTime datePublished, OffsetDateTime dateModified) {
	}

	/** How many comments a review has, and when the latest was written or edited. */
	public record CommentStats(long count, OffsetDateTime latest) {
	}

	/** A review's comments (oldest first: at most one, its product owner's reply) and their stats. */
	public record Thread(List<CommentRow> comments, CommentStats stats) {
	}

	public record CommentRow(long id, long reviewId, long author, String text, OffsetDateTime datePublished,
			OffsetDateTime dateModified) {
	}

	private static final String SELECT = """
			SELECT review_id, "itemReviewed", author, "reviewBody", image, "ratingValue", "bestRating", "worstRating",
			       "datePublished", "dateModified"
			FROM review
			""";

	private static final String SELECT_ID = "SELECT review_id FROM review ";

	private static final String SELECT_COMMENT = """
			SELECT comment_id, review_id, author, text, "datePublished", "dateModified"
			FROM comment
			""";

	private final JdbcClient jdbc;

	ReviewRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(long productId, long author, String reviewBody, String image, Rating rating) {
		return jdbc.sql("""
				INSERT INTO review ("itemReviewed", author, "reviewBody", image, "ratingValue", "bestRating",
				                    "worstRating")
				VALUES (:productId, :author, :reviewBody, :image, :ratingValue, :bestRating, :worstRating)
				RETURNING review_id
				""")
			.param("productId", productId)
			.param("author", author)
			.param("reviewBody", reviewBody)
			.param("image", image)
			.params(rating.params())
			.query(Long.class)
			.single();
	}

	public void update(long id, String reviewBody, String image, Rating rating) {
		jdbc.sql("""
				UPDATE review
				SET "reviewBody" = :reviewBody, image = :image, "ratingValue" = :ratingValue, "bestRating" = :bestRating,
				    "worstRating" = :worstRating
				WHERE review_id = :id
				""")
			.param("id", id)
			.param("reviewBody", reviewBody)
			.param("image", image)
			.params(rating.params())
			.update();
	}

	public void delete(long id) {
		jdbc.sql("DELETE FROM review WHERE review_id = :id").param("id", id).update();
	}

	/**
	 * Locks the review row until the transaction ends, so writes to it take
	 * turns: a precondition checked after this holds until the change commits.
	 * @return whether it exists
	 */
	public boolean lock(long id) {
		return jdbc.sql("SELECT true FROM review WHERE review_id = :id FOR UPDATE")
			.param("id", id)
			.query(Boolean.class)
			.optional()
			.isPresent();
	}

	@Cacheable(cacheNames = Caches.REVIEWS, key = "#id", unless = "#result == null")
	public Optional<Row> find(long id) {
		return jdbc.sql(SELECT + "WHERE review_id = :id").param("id", id).query(ReviewRepository::row).optional();
	}

	/** Newest first, optionally only one author's (at most one, by review_item_author_key). */
	public List<Row> listByProduct(long productId, Long author, int limit, int offset) {
		return jdbc.sql(SELECT + byProduct(author) + """
				ORDER BY "datePublished" DESC, review_id DESC
				LIMIT :limit OFFSET :offset
				""")
			.param("productId", productId)
			.params(byAuthor(author))
			.param("limit", limit)
			.param("offset", offset)
			.query(ReviewRepository::row)
			.list();
	}

	public long countByProduct(long productId) {
		return countByProduct(productId, null);
	}

	public long countByProduct(long productId, Long author) {
		return jdbc.sql("SELECT count(*) FROM review " + byProduct(author))
			.param("productId", productId)
			.params(byAuthor(author))
			.query(Long.class)
			.single();
	}

	/** The review this person wrote of this product, if any. */
	public Optional<Long> findIdByAuthor(long productId, long author) {
		return jdbc.sql(SELECT_ID + byProduct(author))
			.param("productId", productId)
			.params(byAuthor(author))
			.query(Long.class)
			.optional();
	}

	private static String byProduct(Long author) {
		return "WHERE \"itemReviewed\" = :productId" + ((author != null) ? " AND author = :author\n" : "\n");
	}

	private static Map<String, Object> byAuthor(Long author) {
		return (author != null) ? Map.of("author", author) : Map.of();
	}

	/**
	 * Recomputes the product's AggregateRating from its reviews, each rating
	 * scaled to 1-5. "reviewCount" counts the reviews with a body.
	 */
	public void refreshAggregateRating(long productId) {
		// Serialize refreshes per product, so concurrent reviews cannot race.
		jdbc.sql("SELECT pg_advisory_xact_lock(CAST('aggregate_rating'::regclass AS int), CAST(:id AS int))")
			.param("id", productId)
			.query()
			.singleRow();
		int updated = jdbc.sql("""
				INSERT INTO aggregate_rating (product_id, "ratingValue", "ratingCount", "reviewCount")
				SELECT :id,
				       round(avg(1 + 4 * ("ratingValue" - "worstRating") / ("bestRating" - "worstRating")), 1),
				       count(*), count("reviewBody")
				FROM review WHERE "itemReviewed" = :id
				HAVING count(*) > 0
				ON CONFLICT (product_id) DO UPDATE
				SET "ratingValue" = EXCLUDED."ratingValue", "ratingCount" = EXCLUDED."ratingCount",
				    "reviewCount" = EXCLUDED."reviewCount"
				""")
			.param("id", productId)
			.update();
		if (updated == 0) {
			jdbc.sql("DELETE FROM aggregate_rating WHERE product_id = :id").param("id", productId).update();
		}
	}

	public long insertComment(long reviewId, long author, String text) {
		return jdbc.sql("""
				INSERT INTO comment (review_id, author, text) VALUES (:reviewId, :author, :text)
				RETURNING comment_id
				""")
			.param("reviewId", reviewId)
			.param("author", author)
			.param("text", text)
			.query(Long.class)
			.single();
	}

	public void updateComment(long id, String text) {
		jdbc.sql("UPDATE comment SET text = :text WHERE comment_id = :id")
			.param("id", id)
			.param("text", text)
			.update();
	}

	public void deleteComment(long id) {
		jdbc.sql("DELETE FROM comment WHERE comment_id = :id").param("id", id).update();
	}

	/**
	 * Locks the comment row until the transaction ends, so writes to it take
	 * turns: a precondition checked after this holds until the change commits.
	 * @return whether it exists
	 */
	public boolean lockComment(long id) {
		return jdbc.sql("SELECT true FROM comment WHERE comment_id = :id FOR UPDATE")
			.param("id", id)
			.query(Boolean.class)
			.optional()
			.isPresent();
	}

	/** The comment the author wrote on the review: each replies once (comment_review_author_key). */
	public Optional<Long> findCommentId(long reviewId, long author) {
		return jdbc.sql("SELECT comment_id FROM comment WHERE review_id = :reviewId AND author = :author")
			.param("reviewId", reviewId)
			.param("author", author)
			.query(Long.class)
			.optional();
	}

	@Cacheable(cacheNames = Caches.COMMENTS, key = "#id", unless = "#result == null")
	public Optional<CommentRow> findComment(long id) {
		return jdbc.sql(SELECT_COMMENT + "WHERE comment_id = :id")
			.param("id", id)
			.query(ReviewRepository::commentRow)
			.optional();
	}

	/** The comments shown with a review. */
	@Cacheable(cacheNames = Caches.REVIEW_COMMENTS, key = "#reviewId")
	public Thread thread(long reviewId) {
		return new Thread(List.copyOf(commentsOf(List.of(reviewId))), commentStats(reviewId));
	}

	public CommentStats commentStats(long reviewId) {
		return jdbc.sql("""
				SELECT count(*) AS n, max(coalesce("dateModified", "datePublished")) AS latest
				FROM comment WHERE review_id = :id
				""")
			.param("id", reviewId)
			.query((rs, row) -> new CommentStats(rs.getLong("n"), rs.getObject("latest", OffsetDateTime.class)))
			.single();
	}

	/** The comments of the given reviews, oldest first within each review. */
	public List<CommentRow> commentsOf(Collection<Long> reviewIds) {
		if (reviewIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(SELECT_COMMENT + """
				WHERE review_id IN (:ids)
				ORDER BY review_id, "datePublished", comment_id
				""")
			.param("ids", reviewIds)
			.query(ReviewRepository::commentRow)
			.list();
	}

	private static Row row(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getLong("review_id"), rs.getLong("itemReviewed"), rs.getLong("author"),
				rs.getString("reviewBody"), rs.getString("image"),
				new Rating(rs.getBigDecimal("ratingValue"), rs.getBigDecimal("bestRating"),
						rs.getBigDecimal("worstRating")),
				rs.getObject("datePublished", OffsetDateTime.class), rs.getObject("dateModified", OffsetDateTime.class));
	}

	private static CommentRow commentRow(ResultSet rs, int rowNum) throws SQLException {
		return new CommentRow(rs.getLong("comment_id"), rs.getLong("review_id"), rs.getLong("author"),
				rs.getString("text"), rs.getObject("datePublished", OffsetDateTime.class),
				rs.getObject("dateModified", OffsetDateTime.class));
	}

}
