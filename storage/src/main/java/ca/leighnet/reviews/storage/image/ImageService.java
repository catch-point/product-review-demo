package ca.leighnet.reviews.storage.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageService {

	private static final Duration DECODE_WAIT = Duration.ofSeconds(5);

	public record Upload(ImageDoc image, boolean created) {
	}

	private final ImageValidator validator;

	private final ImageStore store;

	private final CurrentUser currentUser;

	private final Iris iris;

	private final DataSize maxSize;

	// Decoding takes memory and CPU in proportion to the pixels: at most one
	// image per processor at a time, and the rest wait their turn briefly.
	private final Semaphore decoding = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));

	ImageService(ImageValidator validator, ImageStore store, CurrentUser currentUser, Iris iris,
			ReviewsProperties properties) {
		this.validator = validator;
		this.store = store;
		this.currentUser = currentUser;
		this.iris = iris;
		this.maxSize = properties.images().maxSize();
	}

	/** Registered people may upload images. */
	public Upload upload(MultipartFile file) {
		currentUser.requirePersonId();
		byte[] bytes = withoutMetadata(read(file));
		var image = decode(bytes);
		var stored = store.save(bytes, image.format());
		return new Upload(toDoc(stored, image), stored.created());
	}

	/**
	 * The image as it is stored, and so decoded: without its metadata, GPS
	 * location included (ImageMetadata). Not an image, it is left for the
	 * validator to refuse.
	 */
	private static byte[] withoutMetadata(byte[] bytes) {
		var format = ImageFormat.detect(bytes);
		if (format.isEmpty()) {
			return bytes;
		}
		try {
			return ImageMetadata.strip(bytes, format.get());
		}
		catch (IllegalArgumentException ex) {
			throw ImageValidator.invalid(format.get());
		}
	}

	private ImageValidator.Image decode(byte[] bytes) {
		try {
			if (!decoding.tryAcquire(DECODE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw ApiException.serviceUnavailable("Too many images are being processed; try again shortly",
						DECODE_WAIT);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw ApiException.serviceUnavailable("The upload was interrupted", DECODE_WAIT);
		}
		try {
			return validator.validate(bytes);
		}
		finally {
			decoding.release();
		}
	}

	/** The image, without its bytes (for HEAD). */
	public ImageStore.Stored find(String id) {
		return store.find(id).orElseThrow(() -> notFound(id));
	}

	/** The image and its bytes, to read once and close. */
	public ImageStore.Opened open(String id) {
		return store.open(id).orElseThrow(() -> notFound(id));
	}

	private static ApiException notFound(String id) {
		return ApiException.notFound("No image " + id);
	}

	// Enforces the limit even when the servlet container's multipart limit is larger.
	private byte[] read(MultipartFile file) {
		if (file.isEmpty()) {
			throw ApiException.badRequest("The upload is empty");
		}
		long limit = maxSize.toBytes();
		if (file.getSize() > limit) {
			throw tooLarge();
		}
		try (InputStream in = file.getInputStream()) {
			byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
			if (bytes.length > limit) {
				throw tooLarge();
			}
			return bytes;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private ApiException tooLarge() {
		return ApiException.payloadTooLarge("Images may be at most " + maxSize.toKilobytes() + " KB");
	}

	private ImageDoc toDoc(ImageStore.Stored stored, ImageValidator.Image image) {
		String url = iris.collection("/images/" + stored.id());
		return new ImageDoc(iris.context(), url, ImageDoc.TYPE, url, image.format().mediaType(), image.width(),
				image.height(), stored.sha256(), stored.uploaded().atOffset(ZoneOffset.UTC));
	}

}
