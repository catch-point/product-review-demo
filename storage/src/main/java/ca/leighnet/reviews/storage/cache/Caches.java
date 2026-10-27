package ca.leighnet.reviews.storage.cache;

import java.util.Collection;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.cache.support.NoOpCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Caches of database rows, keyed by id (Caffeine; see spring.cache.* in
 * application.yml). They hold rows, not documents: documents carry IRIs built
 * from the request's host and a person's email only for some callers.
 * {@link CacheInvalidator} drops entries when the journal records a change.
 *
 * <p>Inside a read-write transaction the caches are bypassed: a write reads
 * what it checks (such as If-Match) and what it returns from the database, and
 * never caches a row it might roll back.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class Caches implements CachingConfigurer {

	/** ProductRepository.Row by product id, with its categories and aggregate rating. */
	public static final String PRODUCTS = "products";

	/** ReviewRepository.Row by review id. */
	public static final String REVIEWS = "reviews";

	/** ReviewRepository.Thread by review id: its comments and their count. */
	public static final String REVIEW_COMMENTS = "reviewComments";

	/** ReviewRepository.CommentRow by comment id. */
	public static final String COMMENTS = "comments";

	/** PersonRepository.Row by person id. */
	public static final String PEOPLE = "people";

	/** person_id by email, looked up on every authenticated request. */
	public static final String PERSON_IDS = "personIds";

	/** PersonRepository.Ownership by person id: the products they own. */
	public static final String OWNERSHIP = "ownership";

	/** Category IRIs: the whole list, and each term or IRI resolved. */
	public static final String CATEGORIES = "categories";

	private final ObjectProvider<CacheManager> cacheManager;

	Caches(ObjectProvider<CacheManager> cacheManager) {
		this.cacheManager = cacheManager;
	}

	@Override
	public CacheResolver cacheResolver() {
		return new CacheResolver() {

			private SimpleCacheResolver caches;

			@Override
			public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
				if (caches == null) {
					caches = new SimpleCacheResolver(cacheManager.getObject());
				}
				var resolved = caches.resolveCaches(context);
				if (TransactionSynchronizationManager.isActualTransactionActive()
						&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
					return resolved.stream().map(cache -> new NoOpCache(cache.getName())).toList();
				}
				return resolved;
			}

		};
	}

}
