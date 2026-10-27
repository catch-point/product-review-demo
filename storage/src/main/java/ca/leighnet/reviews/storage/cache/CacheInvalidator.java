package ca.leighnet.reviews.storage.cache;

import tools.jackson.databind.JsonNode;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Drops the cached rows a journal entry affects. Entries are described by
 * {@code journal_refs()} (V2__journal_notify.sql): the table, its primary key
 * and the rows it points to. This one mapping serves this instance's own
 * commits and every other change announced by NOTIFY.
 */
@Component
public class CacheInvalidator {

	private final CacheManager caches;

	CacheInvalidator(CacheManager caches) {
		this.caches = caches;
	}

	public void apply(JsonNode change) {
		JsonNode pk = change.path("pk");
		switch (change.path("table").asString("")) {
			case "person" -> {
				evict(Caches.PEOPLE, pk.path("person_id"));
				evict(Caches.OWNERSHIP, pk.path("person_id"));
				if (change.path("email").isString()) {
					evict(Caches.PERSON_IDS, change.path("email").asString());
				}
			}
			case "product" -> {
				evict(Caches.PRODUCTS, pk.path("product_id"));
				evict(Caches.OWNERSHIP, change.path("owner"));
				evict(Caches.OWNERSHIP, change.path("oldOwner"));
			}
			case "product_category" -> evict(Caches.PRODUCTS, pk.path("product_id"));
			case "aggregate_rating" -> evict(Caches.PRODUCTS, change.path("product_id"));
			case "review" -> {
				evict(Caches.REVIEWS, pk.path("review_id"));
				evict(Caches.REVIEW_COMMENTS, pk.path("review_id"));
				evict(Caches.PRODUCTS, change.path("itemReviewed"));
			}
			case "comment" -> {
				evict(Caches.COMMENTS, pk.path("comment_id"));
				evict(Caches.REVIEW_COMMENTS, change.path("review_id"));
			}
			case "category" -> clear(Caches.CATEGORIES);
			default -> {
				// Not cached.
			}
		}
	}

	/** Drops everything, after changes may have been missed. */
	public void clearAll() {
		caches.getCacheNames().forEach(this::clear);
	}

	private void evict(String name, JsonNode id) {
		if (id.isIntegralNumber()) {
			evict(name, id.asLong());
		}
	}

	private void evict(String name, Object key) {
		Cache cache = caches.getCache(name);
		if (cache != null) {
			cache.evict(key);
		}
	}

	private void clear(String name) {
		Cache cache = caches.getCache(name);
		if (cache != null) {
			cache.clear();
		}
	}

}
