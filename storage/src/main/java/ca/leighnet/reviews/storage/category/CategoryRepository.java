package ca.leighnet.reviews.storage.category;

import java.util.List;
import java.util.Optional;

import ca.leighnet.reviews.storage.cache.Caches;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {

	private final JdbcClient jdbc;

	CategoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Category IRIs, in term order. */
	@Cacheable(cacheNames = Caches.CATEGORIES, key = "'ids'")
	public List<String> ids() {
		return List.copyOf(jdbc.sql("SELECT id FROM category ORDER BY term").query(String.class).list());
	}

	/** The IRI of a known category, given either its IRI or its term. */
	@Cacheable(cacheNames = Caches.CATEGORIES, key = "'resolve:' + #idOrTerm", unless = "#result == null")
	public Optional<String> resolve(String idOrTerm) {
		return jdbc.sql("SELECT id FROM category WHERE id = :value OR term = :value")
			.param("value", idOrTerm)
			.query(String.class)
			.optional();
	}

}
