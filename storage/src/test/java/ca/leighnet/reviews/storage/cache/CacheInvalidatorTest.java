package ca.leighnet.reviews.storage.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInvalidatorTest {

	private final ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager(Caches.PRODUCTS, Caches.REVIEWS,
			Caches.REVIEW_COMMENTS, Caches.COMMENTS, Caches.PEOPLE, Caches.PERSON_IDS, Caches.OWNERSHIP,
			Caches.CATEGORIES);

	private final CacheInvalidator invalidator = new CacheInvalidator(caches);

	private final JsonMapper json = JsonMapper.builder().build();

	@BeforeEach
	void fill() {
		for (String name : caches.getCacheNames()) {
			for (long id = 1; id <= 3; id++) {
				caches.getCache(name).put(id, "row " + id);
			}
		}
		caches.getCache(Caches.PERSON_IDS).put("olive@example.com", 1L);
		caches.getCache(Caches.CATEGORIES).put("ids", "all");
	}

	@Test
	void aReviewChangeDropsTheReviewItsCommentsAndItsProduct() {
		apply("{\"table\":\"review\",\"pk\":{\"review_id\":2},\"itemReviewed\":3}");

		assertDropped(Caches.REVIEWS, 2);
		assertDropped(Caches.REVIEW_COMMENTS, 2);
		assertDropped(Caches.PRODUCTS, 3);
		assertKept(Caches.REVIEWS, 1);
		assertKept(Caches.PRODUCTS, 2);
		assertKept(Caches.PEOPLE, 2);
	}

	@Test
	void aCommentChangeDropsTheCommentAndItsReviewsThread() {
		apply("{\"table\":\"comment\",\"pk\":{\"comment_id\":1},\"review_id\":2}");

		assertDropped(Caches.COMMENTS, 1);
		assertDropped(Caches.REVIEW_COMMENTS, 2);
		assertKept(Caches.REVIEWS, 2);
	}

	@Test
	void productChangesDropItAndItsOwnersOwnership() {
		apply("{\"table\":\"product\",\"pk\":{\"product_id\":1},\"owner\":2,\"oldOwner\":3}");
		assertDropped(Caches.PRODUCTS, 1);
		assertDropped(Caches.OWNERSHIP, 2);
		assertDropped(Caches.OWNERSHIP, 3);

		apply("{\"table\":\"aggregate_rating\",\"pk\":{\"aggregate_rating_id\":9},\"product_id\":2}");
		assertDropped(Caches.PRODUCTS, 2);

		apply("{\"table\":\"product_category\",\"pk\":{\"product_id\":3,\"category\":\"x\"}}");
		assertDropped(Caches.PRODUCTS, 3);
	}

	@Test
	void aPersonChangeDropsThemAndTheirEmailLookup() {
		apply("{\"table\":\"person\",\"pk\":{\"person_id\":1},\"email\":\"olive@example.com\"}");

		assertDropped(Caches.PEOPLE, 1);
		assertDropped(Caches.OWNERSHIP, 1);
		assertThat(caches.getCache(Caches.PERSON_IDS).get("olive@example.com")).isNull();
	}

	@Test
	void aCategoryChangeDropsEveryCategory() {
		apply("{\"table\":\"category\",\"pk\":{\"term\":\"Audio\"}}");

		assertThat(caches.getCache(Caches.CATEGORIES).get("ids")).isNull();
		assertKept(Caches.PRODUCTS, 1);
	}

	@Test
	void unknownTablesAndMissingKeysChangeNothing() {
		apply("{\"table\":\"journal\",\"pk\":{\"journal_id\":1}}");
		apply("{\"table\":\"review\",\"pk\":{}}");

		assertKept(Caches.REVIEWS, 1);
		assertKept(Caches.PRODUCTS, 1);
	}

	@Test
	void clearAllEmptiesEveryCache() {
		invalidator.clearAll();

		for (String name : caches.getCacheNames()) {
			assertDropped(name, 1);
		}
	}

	private void apply(String change) {
		invalidator.apply(json.readTree(change));
	}

	private void assertDropped(String cache, long id) {
		assertThat(caches.getCache(cache).get(id)).as(cache + "[" + id + "]").isNull();
	}

	private void assertKept(String cache, long id) {
		assertThat(caches.getCache(cache).get(id)).as(cache + "[" + id + "]").isNotNull();
	}

}
