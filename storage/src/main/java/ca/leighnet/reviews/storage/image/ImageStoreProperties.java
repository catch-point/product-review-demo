package ca.leighnet.reviews.storage.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The images' bucket, under {@code reviews.images.store}: an S3 API
 * ({@code endpoint}: MinIO's, as storage/compose.yaml publishes it for local
 * runs), with credentials that may read, write and list the bucket but not
 * delete (storage/minio/init.sh).
 *
 * <p>
 * Apart from ReviewsProperties, which the web server's filters bind while it
 * starts, so these are read only when the bucket's client is made, after
 * every property source is in place (Testcontainers' among them).
 */
@ConfigurationProperties("reviews.images.store")
public record ImageStoreProperties(@DefaultValue("http://localhost:9100") String endpoint,
		@DefaultValue("us-east-1") String region, @DefaultValue("review-images") String bucket,
		@DefaultValue("reviews-images") String accessKey, String secretKey) {
}
