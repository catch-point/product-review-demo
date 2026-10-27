package ca.leighnet.reviews.storage;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * PostgreSQL for the review graph, using the same image as the Docker Compose
 * service, with the same three roles: the container's superuser makes the
 * schema's owner ({@code db/owner.sql}, as storage-db-roles does), migrations
 * run as that owner, {@link #OWNER_USER}, which is not a superuser, and the
 * service connects as {@link #SERVICE_USER}, which the migrations set up
 * (database.ServiceRole). And MinIO for the images, also the Compose image,
 * with the bucket and the service's own user that {@code minio/init.sh} makes
 * (as storage-minio-init does).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String POSTGRES_IMAGE = "postgres:18-alpine";

	/** storage/compose.yaml's x-minio-image. */
	public static final String MINIO_IMAGE = "cgr.dev/chainguard/minio:latest@sha256:ad134b2dd572ae0d989d6807c3e45516cc75a34883a8021db2ff04be5d1d496a";

	public static final String OWNER_USER = "reviews_owner";

	public static final String OWNER_PASSWORD = "a-test-owner-password";

	public static final String SERVICE_USER = "reviews_service";

	public static final String SERVICE_PASSWORD = "a-test-service-password";

	public static final String IMAGES_BUCKET = "review-images";

	public static final String IMAGES_ACCESS_KEY = "reviews-images";

	public static final String IMAGES_SECRET_KEY = "a-test-images-secret";

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
			.withCopyFileToContainer(MountableFile.forHostPath(ProjectFiles.of("db/owner.sql")), "/owner.sql")
			.withEnv("STORAGE_DB_OWNER_PASSWORD", OWNER_PASSWORD);
	}

	@Bean
	MinIOContainer minioContainer() {
		return minio();
	}

	@Bean
	DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) throws Exception {
		postgres.start();
		var owner = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
				"-v", "ON_ERROR_STOP=1", "-v", "owner=" + OWNER_USER, "-v", "service=" + SERVICE_USER, "-f",
				"/owner.sql");
		if (owner.getExitCode() != 0) {
			throw new IllegalStateException("db/owner.sql failed: " + owner.getStderr());
		}
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> SERVICE_USER);
			registry.add("spring.datasource.password", () -> SERVICE_PASSWORD);
			registry.add("spring.flyway.user", () -> OWNER_USER);
			registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
		};
	}

	@Bean
	DynamicPropertyRegistrar imageBucketProperties(MinIOContainer minio) throws Exception {
		start(minio);
		return registry -> {
			registry.add("reviews.images.store.endpoint", minio::getS3URL);
			registry.add("reviews.images.store.bucket", () -> IMAGES_BUCKET);
			registry.add("reviews.images.store.access-key", () -> IMAGES_ACCESS_KEY);
			registry.add("reviews.images.store.secret-key", () -> IMAGES_SECRET_KEY);
		};
	}

	/** MinIO as Compose runs it, with minio/init.sh to run once started ({@link #start}). */
	public static MinIOContainer minio() {
		return new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"))
			.withCopyFileToContainer(MountableFile.forHostPath(ProjectFiles.of("minio/init.sh")), "/init.sh");
	}

	/** Starts MinIO, then makes the bucket and the service's user as storage-minio-init does. */
	public static void start(MinIOContainer minio) throws Exception {
		minio.start();
		var init = minio.execInContainer("env", "MINIO_URL=http://127.0.0.1:9000",
				"MINIO_ROOT_USER=" + minio.getUserName(), "MINIO_ROOT_PASSWORD=" + minio.getPassword(),
				"IMAGES_BUCKET=" + IMAGES_BUCKET, "IMAGES_ACCESS_KEY=" + IMAGES_ACCESS_KEY,
				"IMAGES_SECRET_KEY=" + IMAGES_SECRET_KEY, "bash", "/init.sh");
		if (init.getExitCode() != 0) {
			throw new IllegalStateException("minio/init.sh failed: " + init.getStdout() + init.getStderr());
		}
	}

}
