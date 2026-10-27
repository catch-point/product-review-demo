package ca.leighnet.reviews.storage.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Settings under {@code reviews.*}. */
@ConfigurationProperties("reviews")
public record ReviewsProperties(@DefaultValue Security security, @DefaultValue Cors cors, @DefaultValue Images images,
		@DefaultValue Concurrency concurrency, @DefaultValue Requests requests, @DefaultValue RateLimits rateLimits) {

	/**
	 * Bearer tokens are verified with {@code spring.security.oauth2.resourceserver.jwt.*}
	 * (an identity provider) or, for development and only with {@code devTokens}
	 * on, an HS256 {@code jwtSecret}: such a token is checked for nothing but
	 * its signature and expiry, so whoever holds the secret may be anyone. People are
	 * matched to tokens by the {@code emailClaim}. The {@code roleClaim} holds the role
	 * they registered as ("reviewer" or "owner"); site administrators have the
	 * {@code adminRole} among the {@code rolesClaim} (Keycloak's realm roles).
	 */
	public record Security(@DefaultValue("false") boolean devTokens, String jwtSecret,
			@DefaultValue("email") String emailClaim,
			@DefaultValue("role") String roleClaim, @DefaultValue("roles") String rolesClaim,
			@DefaultValue("admin") String adminRole) {
	}

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}

	/**
	 * Uploaded images are objects in a bucket (image.ImageStoreProperties), named
	 * by their SHA-256. Uploads larger than {@code maxSize}, or with more than
	 * {@code maxPixels} (width times height, against decompression bombs), are
	 * refused.
	 */
	public record Images(@DefaultValue("5MB") DataSize maxSize, @DefaultValue("40000000") long maxPixels) {
	}

	/**
	 * With {@code requirePreconditions}, a PUT or DELETE without If-Match (or
	 * If-Unmodified-Since) is refused with 428 Precondition Required, so no
	 * client can overwrite a change it has not seen.
	 */
	public record Concurrency(@DefaultValue("true") boolean requirePreconditions) {
	}

	/**
	 * Request bodies other than image uploads (JSON documents) may be at most
	 * {@code maxBodySize}; larger ones are refused with 413 before they are read
	 * (web.RequestBodyLimit). Only requests from the {@code trustedProxies} (IP
	 * addresses or CIDR ranges, such as the presentation server's) say who the
	 * client is with X-Forwarded-For (web.ClientAddress); from anyone else it is
	 * ignored.
	 */
	public record Requests(@DefaultValue("64KB") DataSize maxBodySize,
			@DefaultValue List<String> trustedProxies) {
	}

	/**
	 * How many requests each client may make a minute, by kind: reads (GET and
	 * HEAD), writes, and image uploads. A signed-in person is counted by their
	 * email, anyone else by their address; past the limit, 429 with Retry-After
	 * (web.RateLimiter).
	 */
	public record RateLimits(@DefaultValue("true") boolean enabled, @DefaultValue("600") long readsPerMinute,
			@DefaultValue("60") long writesPerMinute, @DefaultValue("10") long uploadsPerMinute) {
	}

}
