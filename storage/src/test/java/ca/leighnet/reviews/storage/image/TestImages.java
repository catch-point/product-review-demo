package ca.leighnet.reviews.storage.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Images for tests, encoded with ImageIO (WebP, which ImageIO cannot write, is
 * a fixed 1x1 lossless file).
 */
public final class TestImages {

	public static final byte[] WEBP_1X1 = Base64.getDecoder()
		.decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

	private TestImages() {
	}

	public static byte[] encode(String format, int width, int height) {
		return encode(format, width, height, 0);
	}

	/** An image of random pixels, which compresses poorly, so it is large. */
	public static byte[] noise(String format, int width, int height) {
		return encode(format, width, height, 42);
	}

	/** The PNG with a chunk added straight after its header (IHDR). */
	public static byte[] withChunk(byte[] png, String type, byte[] data) {
		var crc = new CRC32();
		crc.update(type.getBytes(StandardCharsets.US_ASCII));
		crc.update(data);
		var chunk = ByteBuffer.allocate(12 + data.length)
			.putInt(data.length)
			.put(type.getBytes(StandardCharsets.US_ASCII))
			.put(data)
			.putInt((int) crc.getValue());
		// The signature (8 bytes), then IHDR (12 + 13).
		return concat(slice(png, 0, 33), chunk.array(), slice(png, 33, png.length));
	}

	public static byte[] concat(byte[]... parts) {
		var out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	public static byte[] slice(byte[] bytes, int from, int to) {
		return Arrays.copyOfRange(bytes, from, to);
	}

	private static byte[] encode(String format, int width, int height, long seed) {
		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		if (seed != 0) {
			var random = new Random(seed);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setRGB(x, y, random.nextInt());
				}
			}
		}
		var out = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, format, out)) {
				throw new IllegalArgumentException("No ImageIO writer for " + format);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

}
