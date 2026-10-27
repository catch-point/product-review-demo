package ca.leighnet.reviews.storage.web;

import java.time.Duration;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.person.CurrentUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Limits how many requests each client makes a minute, by kind
 * ({@code reviews.rate-limits}): reads, writes and image uploads each have a
 * bucket of that many requests, refilled over the minute. A signed-in person
 * is counted by their email, wherever they connect from; anyone else by their
 * address ({@link ClientAddress}). Past the limit, 429 with Retry-After, before
 * the request body is read.
 *
 * The buckets are kept in memory, so with several instances each counts its
 * own requests.
 */
@Component
class RateLimiter implements HandlerInterceptor, WebMvcConfigurer {

	enum Kind {

		READ("reads"), WRITE("writes"), UPLOAD("image uploads");

		private final String description;

		Kind(String description) {
			this.description = description;
		}

	}

	private record Key(Kind kind, String client) {
	}

	private static final Duration PERIOD = Duration.ofMinutes(1);

	private final ReviewsProperties.RateLimits limits;

	// Resolved on the first counted request, not at construction: CurrentUser
	// reaches the database, and this WebMvcConfigurer is built while the
	// container is still starting, before dynamic properties (Testcontainers')
	// are applied.
	private final ObjectProvider<CurrentUser> currentUser;

	private final ClientAddress clientAddress;

	// A bucket idle for longer than the period is full again, so it can go.
	private final Cache<Key, Bucket> buckets = Caffeine.newBuilder()
		.expireAfterAccess(PERIOD.multipliedBy(2))
		.maximumSize(100_000)
		.build();

	RateLimiter(ReviewsProperties properties, ObjectProvider<CurrentUser> currentUser) {
		this.limits = properties.rateLimits();
		this.currentUser = currentUser;
		this.clientAddress = new ClientAddress(properties);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		if (limits.enabled()) {
			registry.addInterceptor(this).excludePathPatterns("/livez", "/readyz", "/error");
		}
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Kind kind = kind(request);
		String client = currentUser.getObject().email().map(email -> "person:" + email)
			.orElseGet(() -> "address:" + clientAddress.of(request));
		var bucket = buckets.get(new Key(kind, client), key -> bucket(perMinute(key.kind())));
		var probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw ApiException.tooManyRequests("Too many " + kind.description + ": at most " + perMinute(kind)
					+ " a minute", Duration.ofNanos(probe.getNanosToWaitForRefill()));
		}
		return true;
	}

	static Kind kind(HttpServletRequest request) {
		return switch (request.getMethod()) {
			case "GET", "HEAD", "OPTIONS" -> Kind.READ;
			default -> "/images".equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request)) ? Kind.UPLOAD
					: Kind.WRITE;
		};
	}

	private long perMinute(Kind kind) {
		return switch (kind) {
			case READ -> limits.readsPerMinute();
			case WRITE -> limits.writesPerMinute();
			case UPLOAD -> limits.uploadsPerMinute();
		};
	}

	private static Bucket bucket(long perMinute) {
		return Bucket.builder()
			.addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, PERIOD).build())
			.build();
	}

}
