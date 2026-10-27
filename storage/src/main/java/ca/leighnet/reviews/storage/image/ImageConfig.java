package ca.leighnet.reviews.storage.image;

import java.net.URI;
import java.time.Duration;

import jakarta.servlet.MultipartConfigElement;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import ca.leighnet.reviews.storage.config.ReviewsProperties;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
public class ImageConfig {

	// Multipart encoding adds boundaries and headers around the file.
	private static final DataSize MULTIPART_OVERHEAD = DataSize.ofKilobytes(64);

	/**
	 * Sizes the servlet container's multipart limits from
	 * {@code reviews.images.max-size}, so one setting governs uploads.
	 */
	@Bean
	MultipartConfigElement multipartConfigElement(ReviewsProperties properties) {
		var factory = new MultipartConfigFactory();
		factory.setMaxFileSize(properties.images().maxSize());
		factory.setMaxRequestSize(maxRequestSize(properties));
		return factory.createMultipartConfig();
	}

	/** The largest upload request: the image and its multipart encoding. */
	public static DataSize maxRequestSize(ReviewsProperties properties) {
		return DataSize.ofBytes(properties.images().maxSize().toBytes() + MULTIPART_OVERHEAD.toBytes());
	}

	/**
	 * The image bucket's S3 API ({@link ImageStoreProperties}): MinIO, addressed
	 * by path (http://minio:9000/bucket/key) rather than by a virtual host per
	 * bucket. Checksums are sent and checked only where S3 requires them, as
	 * not every S3-compatible store accepts the newer trailing ones; each image
	 * is named by its SHA-256 anyway. A store that does not answer in time
	 * fails the request with 503 (web.ApiExceptionHandler).
	 */
	@Bean(destroyMethod = "close")
	S3Client imageBucket(ImageStoreProperties store) {
		if (store.secretKey() == null || store.secretKey().isBlank()) {
			throw new IllegalStateException("reviews.images.store.secret-key is empty: set MINIO_IMAGES_SECRET_KEY"
					+ " (the repository root's .env, from scripts/secrets.sh)");
		}
		return S3Client.builder()
			.endpointOverride(URI.create(store.endpoint()))
			.region(Region.of(store.region()))
			.forcePathStyle(true)
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create(store.accessKey(), store.secretKey())))
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.httpClientBuilder(UrlConnectionHttpClient.builder()
				.connectionTimeout(Duration.ofSeconds(2))
				.socketTimeout(Duration.ofSeconds(10)))
			.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15)))
			.build();
	}

	/**
	 * The bucket, in /actuator/health (Spring Boot Admin) as {@code images}. It is
	 * not part of readiness: without it uploads and image reads fail with 503,
	 * but everything else still works.
	 */
	@Bean
	HealthIndicator imagesHealthIndicator(ImageStore store, ImageStoreProperties properties) {
		String bucket = properties.bucket();
		return new AbstractHealthIndicator("The image bucket cannot be reached") {

			@Override
			protected void doHealthCheck(Health.Builder builder) {
				store.checkBucket();
				builder.up().withDetail("bucket", bucket);
			}

		};
	}

}
