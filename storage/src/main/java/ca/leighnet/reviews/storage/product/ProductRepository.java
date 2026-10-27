package ca.leighnet.reviews.storage.product;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ca.leighnet.reviews.storage.cache.Caches;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

	/** The writable columns of a product. */
	public record Fields(String name, String description, String image, String url, Long sameAs, String brand,
			String gtin, String sku, String mpn) {

		// JdbcClient cannot combine a parameter source with individual parameters.
		Map<String, Object> params() {
			Map<String, Object> params = new HashMap<>();
			params.put("name", name);
			params.put("description", description);
			params.put("image", image);
			params.put("url", url);
			params.put("sameAs", sameAs);
			params.put("brand", brand);
			params.put("gtin", gtin);
			params.put("sku", sku);
			params.put("mpn", mpn);
			return params;
		}

	}

	/** {@code changed} is the rating's dateModified, or its dateCreated. */
	public record AggregateRow(BigDecimal ratingValue, BigDecimal bestRating, BigDecimal worstRating, int ratingCount,
			int reviewCount, OffsetDateTime changed) {
	}

	public record Row(long id, Fields fields, Long owner, List<String> categories, AggregateRow aggregateRating,
			OffsetDateTime dateCreated, OffsetDateTime dateModified) {
	}

	/** Products without a rating match no rating bound. */
	public record Filter(String category, BigDecimal minRating, BigDecimal maxRating, int limit, int offset) {
	}

	private static final String SELECT = """
			SELECT r.product_id, r.name, r.description, r.image, r.url, r."sameAs", r.owner,
			       r.brand, r.gtin, r.sku, r.mpn, r."dateCreated", r."dateModified",
			       a."ratingValue", a."bestRating", a."worstRating", a."ratingCount", a."reviewCount",
			       coalesce(a."dateModified", a."dateCreated") AS "ratingChanged",
			       ARRAY(SELECT rc.category FROM product_category rc
			             WHERE rc.product_id = r.product_id ORDER BY rc.category)::text[] AS categories
			FROM product r
			LEFT JOIN aggregate_rating a ON a.product_id = r.product_id
			""";

	private final JdbcClient jdbc;

	ProductRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(Fields fields, long owner) {
		return jdbc.sql("""
				INSERT INTO product (name, description, image, url, "sameAs", owner, brand, gtin, sku, mpn)
				VALUES (:name, :description, :image, :url, :sameAs, :owner, :brand, :gtin, :sku, :mpn)
				RETURNING product_id
				""")
			.params(fields.params())
			.param("owner", owner)
			.query(Long.class)
			.single();
	}

	public void update(long id, Fields fields) {
		jdbc.sql("""
				UPDATE product
				SET name = :name, description = :description, image = :image, url = :url, "sameAs" = :sameAs,
				    brand = :brand, gtin = :gtin, sku = :sku, mpn = :mpn
				WHERE product_id = :id
				""")
			.params(fields.params())
			.param("id", id)
			.update();
	}

	/**
	 * Replaces the categories a product belongs to, touching only rows that change.
	 * @return whether any changed
	 */
	public boolean replaceCategories(long id, List<String> categories) {
		String[] wanted = categories.toArray(String[]::new);
		int removed = jdbc.sql("DELETE FROM product_category WHERE product_id = :id AND NOT (category = ANY(:wanted))")
			.param("id", id)
			.param("wanted", wanted)
			.update();
		int added = jdbc.sql("""
				INSERT INTO product_category (product_id, category)
				SELECT :id, unnest(CAST(:wanted AS text[]))
				ON CONFLICT DO NOTHING
				""")
			.param("id", id)
			.param("wanted", wanted)
			.update();
		return removed + added > 0;
	}

	/**
	 * Stamps dateModified for a change outside the product row (its categories),
	 * so the product's cache validators change too.
	 */
	public void touch(long id) {
		jdbc.sql("UPDATE product SET \"dateModified\" = statement_timestamp() WHERE product_id = :id")
			.param("id", id)
			.update();
	}

	/**
	 * Hides a product and clears references that would keep it in the active
	 * graph. A second call is a no-op.
	 * @return whether this call hid it
	 */
	public boolean discontinue(long id) {
		int updated = jdbc.sql("""
				UPDATE product SET "discontinuedDate" = statement_timestamp()
				WHERE product_id = :id AND "discontinuedDate" IS NULL
				""")
			.param("id", id)
			.update();
		if (updated > 0) {
			jdbc.sql("UPDATE product SET \"sameAs\" = NULL WHERE \"sameAs\" = :id")
				.param("id", id)
				.update();
		}
		return updated > 0;
	}

	public boolean exists(long id) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product
				WHERE product_id = :id AND "discontinuedDate" IS NULL)
				""")
			.param("id", id)
			.query(Boolean.class)
			.single();
	}

	public boolean isDiscontinued(long id) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product
				WHERE product_id = :id AND "discontinuedDate" IS NOT NULL)
				""")
			.param("id", id)
			.query(Boolean.class)
			.single();
	}

	/**
	 * Locks an active product row until the transaction ends, so writes to it
	 * take turns: a precondition checked after this holds until the change
	 * commits.
	 * @return whether it exists and is active
	 */
	public boolean lock(long id) {
		return jdbc.sql("""
				SELECT true FROM product
				WHERE product_id = :id AND "discontinuedDate" IS NULL FOR UPDATE
				""")
			.param("id", id)
			.query(Boolean.class)
			.optional()
			.isPresent();
	}

	/** Locks a product row, including a discontinued one, if it exists. */
	public boolean lockAny(long id) {
		return jdbc.sql("SELECT true FROM product WHERE product_id = :id FOR UPDATE")
			.param("id", id)
			.query(Boolean.class)
			.optional()
			.isPresent();
	}

	@Cacheable(cacheNames = Caches.PRODUCTS, key = "#id", unless = "#result == null")
	public Optional<Row> find(long id) {
		return jdbc.sql(SELECT + "WHERE r.product_id = :id AND r.\"discontinuedDate\" IS NULL")
			.param("id", id)
			.query(this::row)
			.optional();
	}

	/**
	 * The product whether or not it is discontinued. Never cached: a hidden
	 * product must not populate the active product cache.
	 */
	public Optional<Row> findAny(long id) {
		return jdbc.sql(SELECT + "WHERE r.product_id = :id").param("id", id).query(this::row).optional();
	}

	/** The owner, even after the product was discontinued; null when unknown. */
	public Long ownerOf(long id) {
		return jdbc.sql("SELECT owner FROM product WHERE product_id = :id")
			.param("id", id)
			.query(Long.class)
			.optional()
			.orElse(null);
	}

	/** Active products, best rated first. */
	public List<Row> list(Filter filter) {
		return filtered(SELECT + FILTER + """
				ORDER BY a."ratingValue" DESC NULLS LAST, r.name, r.product_id
				LIMIT :limit OFFSET :offset
				""", filter)
			.param("limit", filter.limit())
			.param("offset", filter.offset())
			.query(this::row)
			.list();
	}

	public long count(Filter filter) {
		return filtered("""
				SELECT count(*) FROM product r
				LEFT JOIN aggregate_rating a ON a.product_id = r.product_id
				""" + FILTER, filter).query(Long.class).single();
	}

	private static final String FILTER = """
			WHERE r."discontinuedDate" IS NULL
			  AND (CAST(:category AS text) IS NULL OR EXISTS (
			        SELECT 1 FROM product_category rc
			        WHERE rc.product_id = r.product_id AND rc.category = :category))
			  AND (CAST(:minRating AS numeric) IS NULL OR a."ratingValue" >= :minRating)
			  AND (CAST(:maxRating AS numeric) IS NULL OR a."ratingValue" <= :maxRating)
			""";

	private JdbcClient.StatementSpec filtered(String sql, Filter filter) {
		return jdbc.sql(sql)
			.param("category", filter.category())
			.param("minRating", filter.minRating())
			.param("maxRating", filter.maxRating());
	}

	// pgjdbc cannot read an int4 column with getObject(..., Long.class).
	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private Row row(ResultSet rs, int rowNum) throws SQLException {
		var fields = new Fields(rs.getString("name"), rs.getString("description"), rs.getString("image"),
				rs.getString("url"), nullableLong(rs, "sameAs"), rs.getString("brand"), rs.getString("gtin"),
				rs.getString("sku"), rs.getString("mpn"));
		BigDecimal ratingValue = rs.getBigDecimal("ratingValue");
		AggregateRow aggregate = (ratingValue != null) ? new AggregateRow(ratingValue, rs.getBigDecimal("bestRating"),
				rs.getBigDecimal("worstRating"), rs.getInt("ratingCount"), rs.getInt("reviewCount"),
				rs.getObject("ratingChanged", OffsetDateTime.class)) : null;
		List<String> categories = Arrays.asList((String[]) rs.getArray("categories").getArray());
		return new Row(rs.getLong("product_id"), fields, nullableLong(rs, "owner"), categories, aggregate,
				rs.getObject("dateCreated", OffsetDateTime.class), rs.getObject("dateModified", OffsetDateTime.class));
	}

}
