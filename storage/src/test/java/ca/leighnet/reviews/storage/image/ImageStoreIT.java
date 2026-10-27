package ca.leighnet.reviews.storage.image;

import ca.leighnet.reviews.storage.TestcontainersConfiguration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The image store against MinIO, as Compose runs it: the bucket and the
 * service's user made by minio/init.sh, and the client ImageConfig builds.
 */
class ImageStoreIT {

	private static final MinIOContainer minio = TestcontainersConfiguration.minio();

	private static S3Client s3;

	private static ImageStore store;

	@BeforeAll
	static void startMinio() throws Exception {
		TestcontainersConfiguration.start(minio);
		s3 = client(TestcontainersConfiguration.IMAGES_SECRET_KEY);
		store = new ImageStore(s3, TestcontainersConfiguration.IMAGES_BUCKET);
	}

	@AfterAll
	static void stopMinio() {
		s3.close();
		minio.stop();
	}

	private static S3Client client(String secretKey) {
		return client(TestcontainersConfiguration.IMAGES_ACCESS_KEY, secretKey);
	}

	private static S3Client client(String accessKey, String secretKey) {
		return new ImageConfig().imageBucket(new ImageStoreProperties(minio.getS3URL(), "us-east-1",
				TestcontainersConfiguration.IMAGES_BUCKET, accessKey, secretKey));
	}

	/** When the object was last written, to the millisecond (HEAD's is to the second). */
	private static Instant lastModified(String id) {
		return s3.listObjectsV2(list -> list.bucket(TestcontainersConfiguration.IMAGES_BUCKET).prefix(id))
			.contents()
			.getFirst()
			.lastModified();
	}

	/** MinIO's root user, which may delete, as pruning does. */
	private static S3Client minioRoot() {
		return client(minio.getUserName(), minio.getPassword());
	}

	@Test
	void namesImagesByTheirContent() throws Exception {
		byte[] png = TestImages.encode("png", 2, 2);

		var first = store.save(png, ImageFormat.PNG);
		Instant firstModified = lastModified(first.id());
		Thread.sleep(20);
		var again = store.save(png, ImageFormat.PNG);

		assertThat(first.id()).matches("[0-9a-f]{64}\\.png").startsWith(first.sha256());
		assertThat(first.created()).isTrue();
		assertThat(first.length()).isEqualTo(png.length);
		assertThat(first.uploaded()).isNotNull();
		assertThat(again.id()).isEqualTo(first.id());
		assertThat(again.created()).isFalse();
		assertThat(lastModified(first.id())).as("uploaded again, so not pruned yet").isAfter(firstModified);
		assertThat(again.length()).isEqualTo(png.length);
		try (var opened = store.open(first.id()).orElseThrow()) {
			assertThat(opened.content().readAllBytes()).isEqualTo(png);
			assertThat(opened.image().length()).isEqualTo(png.length);
			assertThat(opened.image().format()).isEqualTo(ImageFormat.PNG);
		}
		assertThat(s3.headObject(head -> head.bucket(TestcontainersConfiguration.IMAGES_BUCKET).key(first.id()))
			.contentType()).isEqualTo("image/png");
	}

	@Test
	void storesAnImagePrunedSinceItsLastUpload() {
		byte[] png = TestImages.encode("png", 4, 4);
		var first = store.save(png, ImageFormat.PNG);
		minioRoot().deleteObject(delete -> delete.bucket(TestcontainersConfiguration.IMAGES_BUCKET).key(first.id()));

		var again = store.save(png, ImageFormat.PNG);

		assertThat(again.created()).isTrue();
		assertThat(store.find(first.id())).isPresent();
	}

	@Test
	void findsStoredImagesOnly() {
		var stored = store.save(TestImages.encode("gif", 2, 2), ImageFormat.GIF);

		assertThat(store.find(stored.id())).get().satisfies(found -> {
			assertThat(found.format()).isEqualTo(ImageFormat.GIF);
			assertThat(found.sha256()).isEqualTo(stored.sha256());
			assertThat(found.length()).isEqualTo(stored.length());
		});
		assertThat(store.find("0".repeat(64) + ".gif")).isEmpty();
		assertThat(store.open("0".repeat(64) + ".gif")).isEmpty();
		assertThat(store.find(stored.sha256() + ".svg")).isEmpty();
		assertThat(store.find("../" + stored.id())).isEmpty();
		assertThat(store.open("/etc/passwd")).isEmpty();
	}

	@Test
	void theServicesUserCannotDeleteImagesOrTouchOtherBuckets() {
		var stored = store.save(TestImages.encode("png", 3, 3), ImageFormat.PNG);

		assertThatExceptionOfType(S3Exception.class)
			.isThrownBy(() -> s3.deleteObject(delete -> delete.bucket(TestcontainersConfiguration.IMAGES_BUCKET)
				.key(stored.id())))
			.satisfies(ex -> assertThat(ex.statusCode()).isEqualTo(403));
		assertThat(store.find(stored.id())).isPresent();
		assertThatExceptionOfType(S3Exception.class)
			.isThrownBy(() -> s3.createBucket(create -> create.bucket("elsewhere")))
			.satisfies(ex -> assertThat(ex.statusCode()).isEqualTo(403));
		assertThatExceptionOfType(S3Exception.class)
			.isThrownBy(() -> s3.putObject(put -> put.bucket("elsewhere").key("x"), RequestBody.fromString("x")))
			.satisfies(ex -> assertThat(ex.statusCode()).isIn(403, 404));
	}

	@Test
	void checksTheBucketWithItsCredentials() {
		store.checkBucket();
		try (var wrong = client("not-the-secret-key")) {
			assertThatExceptionOfType(S3Exception.class)
				.isThrownBy(() -> new ImageStore(wrong, TestcontainersConfiguration.IMAGES_BUCKET).checkBucket());
		}
	}

}
