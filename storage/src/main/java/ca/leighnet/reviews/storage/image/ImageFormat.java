package ca.leighnet.reviews.storage.image;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * The image formats accepted for upload, recognized by their leading bytes
 * (never by the file name or the declared content type).
 */
public enum ImageFormat {

	JPEG("jpg", "image/jpeg", "jpeg"),

	PNG("png", "image/png", "png"),

	GIF("gif", "image/gif", "gif"),

	WEBP("webp", "image/webp", "webp");

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private final String extension;

	private final String mediaType;

	private final String readerName;

	ImageFormat(String extension, String mediaType, String readerName) {
		this.extension = extension;
		this.mediaType = mediaType;
		this.readerName = readerName;
	}

	public String extension() {
		return extension;
	}

	public String mediaType() {
		return mediaType;
	}

	/** The ImageIO format name of the reader that decodes it. */
	String readerName() {
		return readerName;
	}

	public static Optional<ImageFormat> detect(byte[] bytes) {
		if (startsWith(bytes, 0, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) {
			return Optional.of(JPEG);
		}
		if (startsWith(bytes, 0, PNG_SIGNATURE)) {
			return Optional.of(PNG);
		}
		if (startsWith(bytes, 0, ascii("GIF87a")) || startsWith(bytes, 0, ascii("GIF89a"))) {
			return Optional.of(GIF);
		}
		if (startsWith(bytes, 0, ascii("RIFF")) && startsWith(bytes, 8, ascii("WEBP"))) {
			return Optional.of(WEBP);
		}
		return Optional.empty();
	}

	public static Optional<ImageFormat> fromExtension(String extension) {
		return Arrays.stream(values()).filter(format -> format.extension.equals(extension)).findFirst();
	}

	private static boolean startsWith(byte[] bytes, int offset, byte... prefix) {
		if (bytes.length < offset + prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[offset + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

}
