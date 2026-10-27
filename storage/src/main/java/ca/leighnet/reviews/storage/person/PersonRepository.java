package ca.leighnet.reviews.storage.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import ca.leighnet.reviews.storage.cache.Caches;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PersonRepository {

	public record Row(long id, String name, String email, OffsetDateTime dateCreated, OffsetDateTime dateModified) {

		/** When the person last changed: dateModified, or dateCreated. */
		public OffsetDateTime changed() {
			return (dateModified != null) ? dateModified : dateCreated;
		}

	}

	/** The products a person owns, and when the newest was created. */
	public record Ownership(List<Long> productIds, OffsetDateTime latest) {
	}

	private final JdbcClient jdbc;

	PersonRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reserves an id, so the journal can name the new person as the agent of
	 * their own registration.
	 */
	public long nextId() {
		return jdbc.sql("SELECT nextval(pg_get_serial_sequence('person', 'person_id'))").query(Long.class).single();
	}

	public void insert(long id, String name, String email) {
		jdbc.sql("INSERT INTO person (person_id, name, email) VALUES (:id, :name, :email)")
			.param("id", id)
			.param("name", name)
			.param("email", email)
			.update();
	}

	/**
	 * Locks the person row until the transaction ends, so writes to it take
	 * turns: a precondition checked after this holds until the change commits.
	 * @return whether it exists
	 */
	public boolean lock(long id) {
		return jdbc.sql("SELECT true FROM person WHERE person_id = :id FOR UPDATE")
			.param("id", id)
			.query(Boolean.class)
			.optional()
			.isPresent();
	}

	@Cacheable(cacheNames = Caches.PEOPLE, key = "#id", unless = "#result == null")
	public Optional<Row> find(long id) {
		return jdbc.sql("""
				SELECT person_id, name, email, "dateCreated", "dateModified"
				FROM person WHERE person_id = :id
				""")
			.param("id", id)
			.query(PersonRepository::row)
			.optional();
	}

	/** In registration order. */
	public List<Row> list(int limit, int offset) {
		return jdbc.sql("""
				SELECT person_id, name, email, "dateCreated", "dateModified"
				FROM person ORDER BY person_id LIMIT :limit OFFSET :offset
				""")
			.param("limit", limit)
			.param("offset", offset)
			.query(PersonRepository::row)
			.list();
	}

	/** The given people, uncached, as listings are. */
	public List<Row> findAll(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT person_id, name, email, "dateCreated", "dateModified"
				FROM person WHERE person_id IN (:ids)
				""")
			.param("ids", ids)
			.query(PersonRepository::row)
			.list();
	}

	public long count() {
		return jdbc.sql("SELECT count(*) FROM person").query(Long.class).single();
	}

	/** The products each of the given people owns. */
	public Map<Long, List<Long>> ownedProductIds(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT owner, product_id FROM product
				WHERE owner IN (:ids) AND "discontinuedDate" IS NULL
				ORDER BY product_id
				""")
			.param("ids", ids)
			.query((rs, row) -> Map.entry(rs.getLong("owner"), rs.getLong("product_id")))
			.list()
			.stream()
			.collect(Collectors.groupingBy(Map.Entry::getKey,
					Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
	}

	/** Cached only once found: an unregistered email may register at any moment. */
	@Cacheable(cacheNames = Caches.PERSON_IDS, key = "#email", unless = "#result == null")
	public Optional<Long> findIdByEmail(String email) {
		return jdbc.sql("SELECT person_id FROM person WHERE lower(email) = lower(:email)")
			.param("email", email)
			.query(Long.class)
			.optional();
	}

	private static Row row(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getLong("person_id"), rs.getString("name"), rs.getString("email"),
				rs.getObject("dateCreated", OffsetDateTime.class), rs.getObject("dateModified", OffsetDateTime.class));
	}

	public void updateName(long id, String name) {
		jdbc.sql("UPDATE person SET name = :name WHERE person_id = :id")
			.param("id", id)
			.param("name", name)
			.update();
	}

	public void delete(long id) {
		jdbc.sql("DELETE FROM person WHERE person_id = :id").param("id", id).update();
	}

	@Cacheable(cacheNames = Caches.OWNERSHIP, key = "#id")
	public Ownership ownership(long id) {
		var owned = jdbc.sql("""
				SELECT product_id, "dateCreated" FROM product
				WHERE owner = :id AND "discontinuedDate" IS NULL
				ORDER BY product_id
				""")
			.param("id", id)
			.query((rs, row) -> Map.entry(rs.getLong("product_id"), rs.getObject("dateCreated", OffsetDateTime.class)))
			.list();
		return new Ownership(owned.stream().map(Map.Entry::getKey).toList(),
				owned.stream().map(Map.Entry::getValue).max(OffsetDateTime::compareTo).orElse(null));
	}

}
