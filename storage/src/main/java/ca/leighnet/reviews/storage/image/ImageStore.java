package ca.leighnet.reviews.storage.image;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stores images as objects in an S3 bucket ({@link ImageStoreProperties}:
 * MinIO, in Docker), named by the SHA-256 of their content
 * ({@code <sha256>.<extension>}), so identical uploads share one. The
 * service never changes an object's bytes or deletes one: its credentials
 * may only read, write and list (storage/minio/init.sh), and unused images
 * are pruned with MinIO's root credentials instead (scripts/stack.sh).
 */
@Component
public class ImageStore {

	/** An image in the bucket: {@code created} by this upload, or already there. */
	public record Stored(String id, String sha256, ImageFormat format, long length, Instant uploaded,
			boolean created) {
	}

	/** A stored image's bytes, read once; close it to release the connection. */
	public record Opened(Stored image, InputStream content) implements AutoCloseable {

		@Override
		public void close() throws IOException {
			content.close();
		}

	}

	private static final Pattern ID = Pattern.compile("([0-9a-f]{64})\\.([a-z]+)");

	private final S3Client s3;

	private final String bucket;

	@Autowired
	public ImageStore(S3Client s3, ImageStoreProperties properties) {
		this(s3, properties.bucket());
	}

	ImageStore(S3Client s3, String bucket) {
		this.s3 = s3;
		this.bucket = bucket;
	}

	/**
	 * Stores the bytes unless the bucket already has them. The write is
	 * conditional (If-None-Match: *), so of two identical uploads at once, one
	 * creates the object and the other finds it. An image already there is
	 * copied onto itself, which leaves its bytes as they are but makes it
	 * uploaded now: unused images are pruned only some days after their last
	 * upload (scripts/stack.sh storage prune-images), so an image uploaded
	 * again is not pruned while its review is being written.
	 */
	public Stored save(byte[] bytes, ImageFormat format) {
		String sha256 = sha256(bytes);
		String id = sha256 + "." + format.extension();
		boolean created = create(id, bytes, format);
		if (!created && !touch(id, format)) {
			// Pruned between the two: store it again.
			created = create(id, bytes, format);
		}
		var head = head(id).orElseThrow(() -> new IllegalStateException("Image " + id + " was not stored"));
		return new Stored(id, sha256, format, head.contentLength(), head.lastModified(), created);
	}

	/** Writes the object, if there is none (true); false if there is. */
	private boolean create(String id, byte[] bytes, ImageFormat format) {
		try {
			s3.putObject(put -> put.bucket(bucket)
				.key(id)
				.contentType(format.mediaType())
				.contentLength((long) bytes.length)
				.ifNoneMatch("*"), RequestBody.fromBytes(bytes));
			return true;
		}
		catch (S3Exception ex) {
			if (ex.statusCode() != 412 && ex.statusCode() != 409) {
				throw ex;
			}
			return false;
		}
	}

	/** Copies the object onto itself, so its time is now (true); false if it is gone. */
	private boolean touch(String id, ImageFormat format) {
		try {
			s3.copyObject(copy -> copy.sourceBucket(bucket)
				.sourceKey(id)
				.destinationBucket(bucket)
				.destinationKey(id)
				.metadataDirective(MetadataDirective.REPLACE)
				.contentType(format.mediaType()));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return false;
			}
			throw ex;
		}
	}

	/** The stored image with this id, without its bytes; anything that is not an image id is not found. */
	public Optional<Stored> find(String id) {
		return parse(id).flatMap(format -> head(id)
			.map(head -> new Stored(id, sha256Of(id), format, head.contentLength(), head.lastModified(), false)));
	}

	/** The stored image with this id and its bytes, to read once and close. */
	public Optional<Opened> open(String id) {
		var format = parse(id);
		if (format.isEmpty()) {
			return Optional.empty();
		}
		ResponseInputStream<GetObjectResponse> content;
		try {
			content = s3.getObject(get -> get.bucket(bucket).key(id));
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
		var response = content.response();
		return Optional.of(new Opened(new Stored(id, sha256Of(id), format.get(), response.contentLength(),
				response.lastModified(), false), content));
	}

	/** Whether the bucket can be reached with these credentials (the health indicator). */
	public void checkBucket() {
		s3.headBucket(head -> head.bucket(bucket));
	}

	/** The image's format, when the id names one: {@code <sha256>.<extension>}. */
	public static Optional<ImageFormat> parse(String id) {
		var matcher = ID.matcher(id);
		return matcher.matches() ? ImageFormat.fromExtension(matcher.group(2)) : Optional.empty();
	}

	private Optional<HeadObjectResponse> head(String id) {
		try {
			return Optional.of(s3.headObject(head -> head.bucket(bucket).key(id)));
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
		catch (S3Exception ex) {
			// HEAD has no body, so a missing key is a bare 404.
			if (ex.statusCode() == 404) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	private static String sha256Of(String id) {
		return id.substring(0, id.indexOf('.'));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
