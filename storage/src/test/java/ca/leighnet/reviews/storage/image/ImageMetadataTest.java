package ca.leighnet.reviews.storage.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static ca.leighnet.reviews.storage.image.TestImages.concat;
import static ca.leighnet.reviews.storage.image.TestImages.slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ImageMetadataTest {

	private static final String SECRET = "SECRET-49.2827N-123.1207W";

	private final ImageValidator validator = new ImageValidator(1_000_000);

	@Test
	void jpegKeepsOnlyItsOrientation() throws IOException {
		byte[] plain = TestImages.encode("jpeg", 30, 20);
		byte[] tagged = concat(slice(plain, 0, 2), exif(6), segment(0xE1, "http://ns.adobe.com/xap/1.0/\0" + SECRET),
				segment(0xED, "Photoshop 3.0\0" + SECRET), segment(0xFE, SECRET), slice(plain, 2, plain.length),
				ascii(SECRET));

		byte[] stripped = ImageMetadata.strip(tagged, ImageFormat.JPEG);

		assertThat(text(stripped)).doesNotContain(SECRET);
		assertThat(ImageMetadata.strip(stripped, ImageFormat.JPEG)).as("stripping again changes nothing")
			.isEqualTo(stripped);
		// The orientation alone: IFD0 with one entry, 0x0112 SHORT 1 = 6.
		assertThat(indexOf(stripped, new byte[] { 0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0, 6 }))
			.isPositive();
		assertThat(decoded(stripped)).isEqualTo(decoded(plain));
		assertThat(validator.validate(stripped).width()).isEqualTo(30);
	}

	@Test
	void jpegWithoutAnOrientationHasNoExifLeft() {
		byte[] plain = TestImages.encode("jpeg", 8, 8);
		byte[] tagged = concat(slice(plain, 0, 2), exif(1), slice(plain, 2, plain.length));

		byte[] stripped = ImageMetadata.strip(tagged, ImageFormat.JPEG);

		assertThat(text(stripped)).doesNotContain("Exif").doesNotContain(SECRET);
		assertThat(stripped).isEqualTo(plain);
	}

	@Test
	void jpegKeepsItsColourProfile() {
		byte[] plain = TestImages.encode("jpeg", 8, 8);
		byte[] profile = segment(0xE2, "ICC_PROFILE\0\1\1" + "profile bytes");
		byte[] tagged = concat(slice(plain, 0, 2), profile, slice(plain, 2, plain.length));

		assertThat(ImageMetadata.strip(tagged, ImageFormat.JPEG)).isEqualTo(tagged);
	}

	@Test
	void pngKeepsWhatChangesHowItLooks() throws IOException {
		byte[] plain = TestImages.encode("png", 30, 20);
		byte[] tagged = TestImages.withChunk(plain, "tEXt", ascii("Location\0" + SECRET));
		tagged = TestImages.withChunk(tagged, "eXIf", ascii("MM\0*" + SECRET));
		tagged = TestImages.withChunk(tagged, "iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0" + SECRET));
		tagged = TestImages.withChunk(tagged, "prVt", ascii(SECRET));
		tagged = TestImages.withChunk(tagged, "gAMA", new byte[] { 0, 0, (byte) 0xB1, (byte) 0x8F });
		tagged = concat(tagged, ascii(SECRET));

		byte[] stripped = ImageMetadata.strip(tagged, ImageFormat.PNG);

		assertThat(text(stripped)).doesNotContain(SECRET).contains("gAMA");
		assertThat(stripped).isEqualTo(TestImages.withChunk(plain, "gAMA", new byte[] { 0, 0, (byte) 0xB1, (byte) 0x8F }));
		assertThat(decoded(stripped)).isEqualTo(decoded(plain));
	}

	@Test
	void gifKeepsItsLoopingButNotItsComments() throws IOException {
		byte[] plain = TestImages.encode("gif", 30, 20);
		byte[] looping = concat(new byte[] { 0x21, (byte) 0xFF, 11 }, ascii("NETSCAPE2.0"),
				new byte[] { 3, 1, 0, 0, 0 });
		byte[] comment = concat(new byte[] { 0x21, (byte) 0xFE }, subBlock(SECRET), new byte[] { 0 });
		byte[] xmp = concat(new byte[] { 0x21, (byte) 0xFF, 11 }, ascii("XMP DataXMP"), subBlock(SECRET),
				new byte[] { 0 });
		int trailer = plain.length - 1;
		byte[] tagged = concat(slice(plain, 0, trailer), looping, comment, xmp, slice(plain, trailer, plain.length),
				ascii(SECRET));

		byte[] stripped = ImageMetadata.strip(tagged, ImageFormat.GIF);

		assertThat(text(stripped)).doesNotContain(SECRET).doesNotContain("XMP").contains("NETSCAPE2.0");
		assertThat(stripped).isEqualTo(concat(slice(plain, 0, trailer), looping, slice(plain, trailer, plain.length)));
		assertThat(decoded(stripped)).isEqualTo(decoded(plain));
	}

	@Test
	void webpDropsExifAndXmpAndSaysSo() {
		// VP8X: EXIF (0x08) and XMP (0x04) flagged, a 1x1 canvas.
		byte[] vp8x = chunk("VP8X", new byte[] { 0x08 | 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		byte[] image = slice(TestImages.WEBP_1X1, 12, TestImages.WEBP_1X1.length);
		byte[] tagged = riff(vp8x, image, chunk("EXIF", ascii("MM\0*" + SECRET)), chunk("XMP ", ascii(SECRET)));

		byte[] stripped = ImageMetadata.strip(tagged, ImageFormat.WEBP);

		assertThat(text(stripped)).doesNotContain(SECRET).doesNotContain("EXIF").doesNotContain("XMP");
		assertThat(stripped).isEqualTo(riff(chunk("VP8X", new byte[10]), image));
		assertThat(validator.validate(stripped).format()).isEqualTo(ImageFormat.WEBP);
	}

	@Test
	void simpleWebpIsUnchanged() {
		assertThat(ImageMetadata.strip(TestImages.WEBP_1X1, ImageFormat.WEBP)).isEqualTo(TestImages.WEBP_1X1);
	}

	@ParameterizedTest
	@EnumSource(ImageFormat.class)
	void refusesTruncatedImages(ImageFormat format) {
		byte[] whole = format == ImageFormat.WEBP ? TestImages.WEBP_1X1
				: TestImages.encode(format == ImageFormat.JPEG ? "jpeg" : format.extension(), 8, 8);
		byte[] truncated = slice(whole, 0, whole.length / 2);

		assertThatIllegalArgumentException().isThrownBy(() -> ImageMetadata.strip(truncated, format));
	}

	/** An APP1 EXIF segment, little-endian, with an orientation (1 being as stored) and a camera make. */
	private static byte[] exif(int orientation) {
		byte[] make = ascii(SECRET + "\0");
		var tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + make.length).order(ByteOrder.LITTLE_ENDIAN);
		tiff.put(ascii("II")).putShort((short) 42).putInt(8).putShort((short) 2);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putShort((short) 0x010F).putShort((short) 2).putInt(make.length).putInt(8 + 2 + 2 * 12 + 4);
		tiff.putInt(0).put(make);
		return segment(0xE1, concat(ascii("Exif\0\0"), tiff.array()));
	}

	private static byte[] segment(int marker, String payload) {
		return segment(marker, ascii(payload));
	}

	private static byte[] segment(int marker, byte[] payload) {
		return ByteBuffer.allocate(4 + payload.length)
			.put((byte) 0xFF)
			.put((byte) marker)
			.putShort((short) (2 + payload.length))
			.put(payload)
			.array();
	}

	private static byte[] subBlock(String data) {
		return concat(new byte[] { (byte) data.length() }, ascii(data));
	}

	private static byte[] chunk(String fourcc, byte[] data) {
		var chunk = ByteBuffer.allocate(8 + data.length + (data.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
		return chunk.put(ascii(fourcc)).putInt(data.length).put(data).array();
	}

	private static byte[] riff(byte[]... chunks) {
		byte[] body = concat(chunks);
		var header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		header.put(ascii("RIFF")).putInt(4 + body.length).put(ascii("WEBP"));
		return concat(header.array(), body);
	}

	/** The pixels, as decoded. */
	private static int[] decoded(byte[] bytes) throws IOException {
		var image = ImageIO.read(new ByteArrayInputStream(bytes));
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	private static int indexOf(byte[] bytes, byte[] part) {
		outer: for (int i = 0; i <= bytes.length - part.length; i++) {
			for (int j = 0; j < part.length; j++) {
				if (bytes[i + j] != part[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

}
